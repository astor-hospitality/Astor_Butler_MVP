package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.speech.CloudRuWhisperException;
import museon_online.astor_butler.speech.CloudRuWhisperSpeechToText;
import museon_online.astor_butler.speech.SpeechToTextProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One bounded recording turned into text. {@code ASTOR_GLASSES_STT_PROVIDER=cloudru} (default) sends the
 * AAC/MP4 bytes to Cloud.ru whisper-large-v3 with {@code ASTOR_GLASSES_CLOUDRU_API_KEY}; {@code local} keeps
 * the {@code glasses_stt.py} subprocess (image {@code docker/glasses/Dockerfile}) for rollback. Nothing is logged.
 */
@Component
public class GlassesVoice {
    private final boolean enabled;
    private final String python;
    private final String script;
    private final String modelDir;
    private final Path workDir;
    private final long timeoutMs;
    /** Null when the provider is local. */
    private final CloudRuWhisperSpeechToText cloud;
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
                        @Value("${astor.glasses.stt-language:ru}") String language) {
        this(enabled, provider, python, script, modelDir, workDir, timeoutMs,
                SpeechToTextProvider.parse(provider, "ASTOR_GLASSES_STT_PROVIDER") == SpeechToTextProvider.CLOUDRU
                        ? new CloudRuWhisperSpeechToText(cloudBaseUrl, cloudApiKey, "ASTOR_GLASSES_CLOUDRU_API_KEY",
                                cloudModel, language, Duration.ofMillis(Math.max(1, Math.min(30000, timeoutMs))))
                        : null);
    }

    /** Local subprocess provider, as before. */
    public GlassesVoice(boolean enabled, String python, String script, String modelDir, String workDir, long timeoutMs) {
        this(enabled, SpeechToTextProvider.LOCAL.key(), python, script, modelDir, workDir, timeoutMs, null);
    }

    /** Cloud provider with a prepared client (tests point it at a stub). */
    GlassesVoice(boolean enabled, CloudRuWhisperSpeechToText cloud, String workDir, long timeoutMs) {
        this(enabled, SpeechToTextProvider.CLOUDRU.key(), "", "", "", workDir, timeoutMs, cloud);
    }

    private GlassesVoice(boolean enabled, String provider, String python, String script, String modelDir, String workDir,
                         long timeoutMs, CloudRuWhisperSpeechToText cloud) {
        SpeechToTextProvider selected = SpeechToTextProvider.parse(provider, "ASTOR_GLASSES_STT_PROVIDER");
        this.enabled = enabled;
        this.python = python;
        this.script = script;
        this.modelDir = modelDir;
        this.workDir = Path.of(workDir);
        this.timeoutMs = Math.max(1, Math.min(30000, timeoutMs));
        this.cloud = selected == SpeechToTextProvider.CLOUDRU ? cloud : null;
    }

    SpeechToTextProvider provider() { return cloud == null ? SpeechToTextProvider.LOCAL : SpeechToTextProvider.CLOUDRU; }

    boolean ready() { return enabled && Instant.now().isBefore(readyUntil); }

    String transcribe(byte[] audio) {
        if (!enabled) throw unavailable();
        if (cloud != null) return transcribeCloud(audio);
        Path directory = null;
        Path file = null;
        boolean safeToDelete = true;
        try {
            Files.createDirectories(workDir);
            directory = Files.createTempDirectory(workDir, "request-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            file = Files.createTempFile(directory, "input-", ".m4a",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(file, audio);
            String output = GlassesProcess.run(List.of(python, script, "--model-dir", modelDir,
                    file.toAbsolutePath().toString()), Duration.ofMillis(timeoutMs));
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
                if (file != null && safeToDelete) Files.deleteIfExists(file);
                if (directory != null && safeToDelete) Files.deleteIfExists(directory);
            } catch (Exception e) {
                readyUntil = Instant.MIN;
                throw unavailable();
            }
        }
    }

    /**
     * The bytes go straight to Cloud.ru, no temp file and no local decoder. Empty text means no speech; a
     * 4xx that rejects the media means malformed input; anything else makes the provider unavailable.
     * The 30-second bound of the local validator does not apply: the controller's 2 MB cap is the limit.
     */
    private String transcribeCloud(byte[] audio) {
        try {
            String text = cloud.transcribe(audio, "input.m4a", "audio/mp4");
            if (text.isBlank()) {
                readyUntil = Instant.now().plusSeconds(300);
                throw new GlassesFailure(400, "NO_SPEECH", "Speech was not detected; record a short phrase again");
            }
            if (text.codePointCount(0, text.length()) > 4000) throw unavailable();
            readyUntil = Instant.now().plusSeconds(300);
            return text;
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

    private GlassesFailure unavailable() {
        return new GlassesFailure(503, "VOICE_UNAVAILABLE", "Voice provider unavailable or not configured");
    }
}
