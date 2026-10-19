package museon_online.astor_butler.telegram.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.speech.SpeechToTextResult;
import museon_online.astor_butler.speech.SpeechToTextService;
import museon_online.astor_butler.storage.ObjectStorageResult;
import museon_online.astor_butler.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramVoiceTranscriptionService {

    private final SpeechToTextService speechToTextService;
    private final ObjectStorageService objectStorageService;

    @Value("${telegram.voice.download-enabled:true}")
    private boolean downloadEnabled;

    @Value("${telegram.voice.max-duration-seconds:60}")
    private long maxDurationSeconds;

    @Value("${telegram.voice.max-file-size-bytes:10485760}")
    private long maxFileSizeBytes;

    @Value("${astor.speech-to-text.work-dir:/private/tmp/astor-butler-stt}")
    private String workDir;

    @Value("${astor.speech-to-text.keep-local-files:false}")
    private boolean keepLocalFiles;

    @Value("${telegram.bot.token:}")
    private String botToken;

    /** The bot's own proxy (see TelegramBotConfig): where Telegram is reachable only through it, so is the file. */
    @Value("${telegram.bot.proxy.type:NO_PROXY}")
    private String proxyType;

    @Value("${telegram.bot.proxy.host:}")
    private String proxyHost;

    @Value("${telegram.bot.proxy.port:0}")
    private int proxyPort;

    @Value("${telegram.voice.download-timeout-ms:15000}")
    private int downloadTimeoutMs;

    public IncomingMessage enrich(IncomingMessage incoming, AbsSender sender) {
        if (!downloadEnabled || incoming == null || sender == null || incoming.payload() == null) {
            return incoming;
        }

        Object mediaKind = incoming.payload().get("mediaKind");
        Object fileId = incoming.payload().get("telegramFileId");
        if (!"VOICE".equals(mediaKind) && !"AUDIO".equals(mediaKind)) {
            return incoming;
        }
        if (fileId == null || fileId.toString().isBlank()) {
            return withTranscriptionStatus(incoming, SpeechToTextResult.failed("Telegram file id is empty", incoming.payload()));
        }

        Map<String, Object> payload = new LinkedHashMap<>(incoming.payload());
        SpeechToTextResult limitResult = guardVoiceLimits(payload);
        if (limitResult != null) {
            return withTranscriptionStatus(incoming, limitResult);
        }
        try {
            Path audioFile = download(sender, fileId.toString(), incoming);
            payload.put("localAudioPath", audioFile.toString());
            ObjectStorageResult objectStorage = objectStorageService.uploadTelegramVoice(
                    audioFile,
                    incoming.chatId(),
                    incoming.telegramMessageId(),
                    string(payload.get("mimeType"))
            );
            payload.put("storageBucket", objectStorage.bucket());
            payload.put("storageObjectKey", objectStorage.objectKey());
            payload.put("storagePublicUrl", objectStorage.publicUrl());
            payload.put("storageTtlDays", objectStorage.ttlDays());
            payload.put("storageLifecycle", "EXPIRE_AFTER_%s_DAYS".formatted(objectStorage.ttlDays()));
            SpeechToTextResult result = speechToTextService.transcribe(audioFile, payload);
            payload.put("transcriptionAvailable", result.available());
            payload.put("transcriptionStatus", transcriptionStatus(result));
            payload.put("transcriptionReason", result.reason());
            payload.put("transcriptionMetadata", result.metadata());
            if (result.transcribed()) {
                payload.put("transcript", result.text());
                cleanupLocalFile(audioFile);
                return incoming.withTextAndPayload(result.text(), payload);
            }
            cleanupLocalFile(audioFile);
            return incoming.withTextAndPayload(incoming.text(), payload);
        } catch (Exception e) {
            log.warn("Telegram voice enrichment failed: {}", e.getMessage());
            payload.put("transcriptionAvailable", false);
            payload.put("transcriptionStatus", "FAILED");
            payload.put("transcriptionReason", e.getClass().getSimpleName() + ": " + e.getMessage());
            return incoming.withTextAndPayload(incoming.text(), payload);
        }
    }

    private SpeechToTextResult guardVoiceLimits(Map<String, Object> payload) {
        long durationSeconds = longValue(payload.get("durationSeconds"));
        if (maxDurationSeconds > 0 && durationSeconds > maxDurationSeconds) {
            return SpeechToTextResult.failed(
                    "VOICE_DURATION_LIMIT_EXCEEDED",
                    Map.of("durationSeconds", durationSeconds, "maxDurationSeconds", maxDurationSeconds)
            );
        }
        long fileSizeBytes = longValue(payload.get("fileSizeBytes"));
        if (maxFileSizeBytes > 0 && fileSizeBytes > maxFileSizeBytes) {
            return SpeechToTextResult.failed(
                    "VOICE_FILE_SIZE_LIMIT_EXCEEDED",
                    Map.of("fileSizeBytes", fileSizeBytes, "maxFileSizeBytes", maxFileSizeBytes)
            );
        }
        return null;
    }

    private IncomingMessage withTranscriptionStatus(IncomingMessage incoming, SpeechToTextResult result) {
        Map<String, Object> payload = new LinkedHashMap<>(incoming.payload());
        payload.put("transcriptionAvailable", result.available());
        payload.put("transcriptionStatus", transcriptionStatus(result));
        payload.put("transcriptionReason", result.reason());
        return incoming.withTextAndPayload(incoming.text(), payload);
    }

    private String transcriptionStatus(SpeechToTextResult result) {
        if (result == null) {
            return "FAILED";
        }
        if (result.transcribed()) {
            return "TRANSCRIBED";
        }
        return result.available() ? "FAILED" : "PENDING";
    }

    private Path download(AbsSender sender, String fileId, IncomingMessage incoming) throws Exception {
        org.telegram.telegrambots.meta.api.objects.File telegramFile = sender.execute(GetFile.builder()
                .fileId(fileId)
                .build());

        Path dir = Files.createDirectories(Path.of(workDir));
        String suffix = suffix(telegramFile == null ? null : telegramFile.getFilePath(), incoming.payload());
        Path target = dir.resolve("%s-%s%s".formatted(
                incoming.correlationId() == null ? "telegram" : incoming.correlationId(),
                fileId.replaceAll("[^a-zA-Z0-9_-]", "_"),
                suffix
        ));

        if (telegramFile == null || telegramFile.getFilePath() == null || telegramFile.getFilePath().isBlank()) {
            throw new IllegalStateException("Telegram file path is empty");
        }
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException("Telegram bot token is empty");
        }

        URLConnection connection = URI.create(telegramFile.getFileUrl(botToken)).toURL().openConnection(downloadProxy());
        int timeout = Math.max(1000, downloadTimeoutMs);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        try (InputStream inputStream = connection.getInputStream()) {
            Files.copy(inputStream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    Proxy downloadProxy() {
        if (proxyType == null || proxyHost == null || proxyHost.isBlank() || proxyPort <= 0) {
            return Proxy.NO_PROXY;
        }
        Proxy.Type type = switch (proxyType.trim().toUpperCase(java.util.Locale.ROOT).replace("-", "_")) {
            case "HTTP" -> Proxy.Type.HTTP;
            case "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS;
            default -> Proxy.Type.DIRECT;
        };
        return type == Proxy.Type.DIRECT ? Proxy.NO_PROXY
                : new Proxy(type, InetSocketAddress.createUnresolved(proxyHost.trim(), proxyPort));
    }

    private String suffix(String filePath, Map<String, Object> payload) {
        if (filePath != null && filePath.contains(".")) {
            return filePath.substring(filePath.lastIndexOf('.'));
        }
        Object mimeType = payload == null ? null : payload.get("mimeType");
        if ("audio/mpeg".equals(mimeType)) {
            return ".mp3";
        }
        if ("audio/wav".equals(mimeType)) {
            return ".wav";
        }
        return ".ogg";
    }

    private void cleanupLocalFile(Path audioFile) {
        if (keepLocalFiles || audioFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(audioFile);
        } catch (Exception e) {
            log.debug("Cannot delete local voice temp file {}: {}", audioFile, e.getMessage());
        }
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
