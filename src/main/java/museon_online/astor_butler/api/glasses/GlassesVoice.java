package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class GlassesVoice {
    private final boolean enabled;
    private final String python;
    private final String script;
    private final String modelDir;
    private final Path workDir;
    private final long timeoutMs;
    private volatile Instant readyUntil = Instant.MIN;
    private final ObjectMapper mapper = new ObjectMapper();

    public GlassesVoice(@Value("${astor.glasses.voice-enabled:false}") boolean enabled,
                        @Value("${astor.glasses.python:python3}") String python,
                        @Value("${astor.glasses.stt-script:/app/glasses_stt.py}") String script,
                        @Value("${astor.glasses.stt-model-dir:/models/whisper-base}") String modelDir,
                        @Value("${astor.glasses.work-dir:/tmp/astor-glasses}") String workDir,
                        @Value("${astor.glasses.stt-timeout-ms:20000}") long timeoutMs) {
        this.enabled = enabled;
        this.python = python;
        this.script = script;
        this.modelDir = modelDir;
        this.workDir = Path.of(workDir);
        this.timeoutMs = Math.max(1, Math.min(30000, timeoutMs));
    }

    boolean ready() { return enabled && Instant.now().isBefore(readyUntil); }

    String transcribe(byte[] audio) {
        if (!enabled) throw unavailable();
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

    private GlassesFailure unavailable() {
        return new GlassesFailure(503, "VOICE_UNAVAILABLE", "Voice provider unavailable or not configured");
    }
}
