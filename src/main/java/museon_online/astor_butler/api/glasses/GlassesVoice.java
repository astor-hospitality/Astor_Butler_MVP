package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.speech.CloudRuWhisperException;
import museon_online.astor_butler.speech.CloudRuWhisperSpeechToText;
import museon_online.astor_butler.speech.SpeechToTextException;
import museon_online.astor_butler.speech.SpeechToTextProvider;
import museon_online.astor_butler.speech.YandexSpeechKitSpeechToText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One bounded recording turned into text. {@code ASTOR_GLASSES_STT_PROVIDER=cloudru} (default) sends the
 * AAC/MP4 bytes to Cloud.ru whisper-large-v3 with {@code ASTOR_GLASSES_CLOUDRU_API_KEY}; {@code yandex} transcodes
 * them to Ogg Opus with an {@code ffmpeg} subprocess ({@code ASTOR_GLASSES_FFMPEG}) and sends that to Yandex
 * SpeechKit v1 with {@code ASTOR_GLASSES_STT_API_KEY} (else {@code YANDEX_SPEECHKIT_API_KEY}); {@code local} keeps
 * the {@code glasses_stt.py} subprocess (image {@code docker/glasses/Dockerfile}) for rollback. Nothing is logged.
 */
@Component
public class GlassesVoice {
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    /** The contract's maximum recording: a transcode never sends SpeechKit more than this. */
    private static final String MAX_SECONDS = "30";

    private final boolean enabled;
    private final SpeechToTextProvider provider;
    private final String python;
    private final String script;
    private final String modelDir;
    private final String ffmpeg;
    private final Path workDir;
    private final long timeoutMs;
    /** Null unless the provider is cloudru. */
    private final CloudRuWhisperSpeechToText cloud;
    /** Null unless the provider is yandex. */
    private final YandexSpeechKitSpeechToText speechKit;
    private volatile Instant readyUntil = Instant.MIN;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GlassesVoice(@Value("${astor.glasses.voice-enabled:false}") boolean enabled,
                        @Value("${astor.glasses.stt-provider:" + SpeechToTextProvider.DEFAULT + "}") String provider,
                        @Value("${astor.glasses.python:python3}") String python,
                        @Value("${astor.glasses.stt-script:/app/glasses_stt.py}") String script,
                        @Value("${astor.glasses.stt-model-dir:/models/whisper-base}") String modelDir,
                        @Value("${astor.glasses.work-dir:/tmp/astor-glasses}") String workDir,
                        @Value("${astor.glasses.stt-timeout-ms:20000}") long timeoutMs,
                        @Value("${cloudru.base-url:}") String cloudBaseUrl,
                        @Value("${astor.glasses.cloudru-api-key:}") String cloudApiKey,
                        @Value("${astor.glasses.stt-cloudru-model:" + CloudRuWhisperSpeechToText.DEFAULT_MODEL + "}") String cloudModel,
                        @Value("${astor.glasses.stt-language:ru}") String language,
                        @Value("${astor.glasses.stt-api-key:${yandex.speechkit.api-key:}}") String speechKitApiKey,
                        @Value("${astor.glasses.stt-endpoint:${yandex.speechkit.stt-endpoint:}}") String speechKitEndpoint,
                        @Value("${astor.glasses.ffmpeg:ffmpeg}") String ffmpeg) {
        this(enabled, SpeechToTextProvider.parse(provider, "ASTOR_GLASSES_STT_PROVIDER"), python, script, modelDir, workDir,
                timeoutMs,
                cloudClient(provider, cloudBaseUrl, cloudApiKey, cloudModel, language, timeoutMs),
                speechKitClient(provider, speechKitEndpoint, speechKitApiKey, language, timeoutMs), ffmpeg);
    }

    /** Local subprocess provider, as before. */
    public GlassesVoice(boolean enabled, String python, String script, String modelDir, String workDir, long timeoutMs) {
        this(enabled, SpeechToTextProvider.LOCAL, python, script, modelDir, workDir, timeoutMs, null, null, "");
    }

    /** Cloud provider with a prepared client (tests point it at a stub). */
    GlassesVoice(boolean enabled, CloudRuWhisperSpeechToText cloud, String workDir, long timeoutMs) {
        this(enabled, SpeechToTextProvider.CLOUDRU, "", "", "", workDir, timeoutMs, cloud, null, "");
    }

    /** SpeechKit provider with a prepared client and an ffmpeg command (tests inject a script and a stub). */
    GlassesVoice(boolean enabled, YandexSpeechKitSpeechToText speechKit, String ffmpeg, String workDir, long timeoutMs) {
        this(enabled, SpeechToTextProvider.YANDEX, "", "", "", workDir, timeoutMs, null, speechKit, ffmpeg);
    }

    private GlassesVoice(boolean enabled, SpeechToTextProvider provider, String python, String script, String modelDir,
                         String workDir, long timeoutMs, CloudRuWhisperSpeechToText cloud,
                         YandexSpeechKitSpeechToText speechKit, String ffmpeg) {
        this.enabled = enabled;
        this.provider = provider;
        this.python = python;
        this.script = script;
        this.modelDir = modelDir;
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg.trim();
        this.workDir = Path.of(workDir);
        this.timeoutMs = bound(timeoutMs).toMillis();
        this.cloud = provider == SpeechToTextProvider.CLOUDRU ? cloud : null;
        this.speechKit = provider == SpeechToTextProvider.YANDEX ? speechKit : null;
    }

    private static Duration bound(long timeoutMs) { return Duration.ofMillis(Math.max(1, Math.min(30000, timeoutMs))); }

    private static CloudRuWhisperSpeechToText cloudClient(String provider, String baseUrl, String apiKey, String model,
                                                          String language, long timeoutMs) {
        if (SpeechToTextProvider.parse(provider, "ASTOR_GLASSES_STT_PROVIDER") != SpeechToTextProvider.CLOUDRU) return null;
        return new CloudRuWhisperSpeechToText(baseUrl, apiKey, "ASTOR_GLASSES_CLOUDRU_API_KEY", model, language, bound(timeoutMs));
    }

    private static YandexSpeechKitSpeechToText speechKitClient(String provider, String endpoint, String apiKey, String language,
                                                               long timeoutMs) {
        if (SpeechToTextProvider.parse(provider, "ASTOR_GLASSES_STT_PROVIDER") != SpeechToTextProvider.YANDEX) return null;
        return new YandexSpeechKitSpeechToText(new YandexSpeechKitSpeechToText.Settings(endpoint, apiKey, language, bound(timeoutMs)));
    }

    SpeechToTextProvider provider() { return provider; }

    boolean ready() { return enabled && Instant.now().isBefore(readyUntil); }

    String transcribe(byte[] audio) {
        if (!enabled) throw unavailable();
        return switch (provider) {
            case CLOUDRU -> transcribeCloud(audio);
            case YANDEX -> transcribeSpeechKit(audio);
            case LOCAL -> transcribeLocal(audio);
        };
    }

    private String transcribeLocal(byte[] audio) {
        return inScratch(audio, (input, directory) -> {
            String output = GlassesProcess.run(List.of(python, script, "--model-dir", modelDir,
                    input.toAbsolutePath().toString()), Duration.ofMillis(timeoutMs));
            var result = mapper.readTree(output);
            String status = result.path("status").asText();
            if (status.equals("malformed")) throw new GlassesFailure(400, "MALFORMED_AUDIO", "Invalid AAC mono 16kHz media");
            if (status.equals("too_large")) throw new GlassesFailure(413, "AUDIO_TOO_LONG", "Audio exceeds 30 seconds");
            if (status.equals("no_speech")) {
                readyUntil = Instant.now().plusSeconds(300);
                throw new GlassesFailure(400, "NO_SPEECH", "Speech was not detected; record a short phrase again");
            }
            String text = result.path("text").asText("").trim();
            if (!status.equals("transcribed") || text.isBlank() || text.codePointCount(0, text.length()) > 4000) {
                throw unavailable();
            }
            readyUntil = Instant.now().plusSeconds(300);
            return text;
        });
    }

    /**
     * The bytes go straight to Cloud.ru, no temp file and no local decoder. Empty text means no speech; a
     * 4xx that rejects the media means malformed input; anything else makes the provider unavailable.
     * The 30-second bound of the local validator does not apply: the controller's 2 MB cap is the limit.
     */
    private String transcribeCloud(byte[] audio) {
        try {
            return accept(cloud.transcribe(audio, "input.m4a", "audio/mp4"));
        } catch (GlassesFailure e) {
            if (e.status == 503) readyUntil = Instant.MIN;
            throw e;
        } catch (CloudRuWhisperException e) {
            // Status and reason stay here: the phone only learns the category.
            if (e.status() == 413) throw new GlassesFailure(413, "AUDIO_TOO_LONG", "Audio exceeds the provider limit");
            if (e.status() == 400 || e.status() == 415 || e.status() == 422) {
                throw new GlassesFailure(400, "MALFORMED_AUDIO", "Invalid AAC mono 16kHz media");
            }
            readyUntil = Instant.MIN;
            throw unavailable();
        } catch (RuntimeException e) {
            readyUntil = Instant.MIN;
            throw unavailable();
        }
    }

    /**
     * SpeechKit sync recognition takes Ogg Opus, so the phone's MP4/AAC is transcoded first by ffmpeg in a private
     * scratch directory; a recording that already is Ogg goes out as it is. ffmpeg refusing the input is malformed
     * media; SpeechKit's outcomes map like the cloud path. The sync limit of 1 MB is checked before anything leaves.
     */
    private String transcribeSpeechKit(byte[] audio) {
        if (!speechKit.configured()) {
            readyUntil = Instant.MIN;
            throw unavailable();
        }
        byte[] opus = YandexSpeechKitSpeechToText.isOgg(audio) ? audio : inScratch(audio, this::transcode);
        if (opus.length > YandexSpeechKitSpeechToText.MAX_SYNC_BYTES) {
            throw new GlassesFailure(413, "AUDIO_TOO_LONG", "Audio exceeds the provider limit");
        }
        try {
            return accept(speechKit.transcribe(opus));
        } catch (GlassesFailure e) {
            if (e.status == 503) readyUntil = Instant.MIN;
            throw e;
        } catch (SpeechToTextException e) {
            // Status and reason stay here: the phone only learns the category.
            if (e.status() == 413) throw new GlassesFailure(413, "AUDIO_TOO_LONG", "Audio exceeds the provider limit");
            if (e.status() == 400 || e.status() == 415 || e.status() == 422) {
                throw new GlassesFailure(400, "MALFORMED_AUDIO", "Invalid AAC mono 16kHz media");
            }
            readyUntil = Instant.MIN;
            throw unavailable();
        } catch (RuntimeException e) {
            readyUntil = Instant.MIN;
            throw unavailable();
        }
    }

    /**
     * First audio stream only, mono, 16 kHz, Opus for speech in an Ogg container, cut at the contract's 30 seconds
     * (the local decoder trimmed the same padding). A non-zero exit is ffmpeg rejecting the input; ffmpeg not
     * producing Ogg is a broken tool, which is the provider's problem, not the recording's.
     */
    private byte[] transcode(Path input, Path directory) throws Exception {
        Path output = Files.createFile(directory.resolve("output.ogg"), PRIVATE_FILE);
        var result = GlassesProcess.execute(List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-i", input.toAbsolutePath().toString(), "-map", "0:a:0", "-t", MAX_SECONDS, "-ac", "1", "-ar", "16000",
                "-c:a", "libopus", "-b:a", "32k", "-application", "voip", "-f", "ogg",
                output.toAbsolutePath().toString()), Duration.ofMillis(timeoutMs));
        if (result.exitCode() != 0) throw new GlassesFailure(400, "MALFORMED_AUDIO", "Invalid AAC mono 16kHz media");
        byte[] opus = Files.readAllBytes(output);
        if (!YandexSpeechKitSpeechToText.isOgg(opus)) throw unavailable();
        return opus;
    }

    /** The shared acceptance of a cloud transcript: blank is silence, over 4000 code points is not an answer. */
    private String accept(String text) {
        if (text.isBlank()) {
            readyUntil = Instant.now().plusSeconds(300);
            throw new GlassesFailure(400, "NO_SPEECH", "Speech was not detected; record a short phrase again");
        }
        if (text.codePointCount(0, text.length()) > 4000) throw unavailable();
        readyUntil = Instant.now().plusSeconds(300);
        return text;
    }

    private interface ScratchWork<T> {
        T apply(Path input, Path directory) throws Exception;
    }

    /**
     * Writes the recording into a fresh private directory under the work dir ({@code rwx------}, file
     * {@code rw-------}), runs the work and deletes everything afterwards, unless a subprocess could not be stopped,
     * in which case the files stay out of reach of a still-running reader. Any failure the work does not map itself
     * is "unavailable" and resets readiness.
     */
    private <T> T inScratch(byte[] audio, ScratchWork<T> work) {
        Path directory = null;
        boolean safeToDelete = true;
        try {
            Files.createDirectories(workDir);
            directory = Files.createTempDirectory(workDir, "request-", PRIVATE_DIRECTORY);
            Path input = Files.createTempFile(directory, "input-", ".m4a", PRIVATE_FILE);
            Files.write(input, audio);
            return work.apply(input, directory);
        } catch (GlassesFailure e) {
            if (e.status == 503) readyUntil = Instant.MIN;
            throw e;
        } catch (Exception e) {
            if (e instanceof GlassesProcess.UnsafeCleanupException) safeToDelete = false;
            readyUntil = Instant.MIN;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            try {
                if (directory != null && safeToDelete) deleteScratch(directory);
            } catch (Exception e) {
                readyUntil = Instant.MIN;
                throw unavailable();
            }
        }
    }

    private static void deleteScratch(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) Files.deleteIfExists(entry);
        }
        Files.deleteIfExists(directory);
    }

    private GlassesFailure unavailable() {
        return new GlassesFailure(503, "VOICE_UNAVAILABLE", "Voice provider unavailable or not configured");
    }
}
