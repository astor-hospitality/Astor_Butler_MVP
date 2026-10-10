package museon_online.astor_butler.api.glasses;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded draining, timeout and process-tree cleanup. Never logs command/output/errors. */
final class GlassesProcess {
    /** Exit status and standard output of a process that ended within the timeout. */
    record Result(int exitCode, String stdout) { }

    /** Runs the command; anything but a clean exit within the timeout is "Process unavailable". */
    static String run(List<String> command, Duration timeout) throws Exception {
        Result result = execute(command, timeout);
        if (result.exitCode() != 0) throw new IllegalStateException("Process unavailable");
        return result.stdout();
    }

    /**
     * Like {@link #run}, but a non-zero exit comes back in the result instead of being thrown, so a caller can
     * tell "the tool rejected this input" from "the tool did not run". Timeout and output overflow still throw.
     */
    static Result execute(List<String> command, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        // Do not pass unrelated cloud/Telegram/database credentials to the local decoder.
        Map<String, String> original = new HashMap<>(builder.environment());
        builder.environment().clear();
        for (String key : List.of("PATH", "SYSTEMROOT", "LANG", "LC_ALL")) {
            if (original.containsKey(key)) builder.environment().put(key, original.get(key));
        }
        builder.environment().put("HF_HUB_OFFLINE", "1");
        builder.environment().put("OMP_NUM_THREADS", "2");
        Process process = builder.start();
        Set<ProcessHandle> children = new HashSet<>();
        AtomicBoolean overflow = new AtomicBoolean();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Thread out = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout, overflow));
        Thread err = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), null, overflow));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            do {
                process.descendants().forEach(children::add);
                if (overflow.get() || System.nanoTime() >= deadline) throw new IllegalStateException("Process unavailable");
            } while (!process.waitFor(20, TimeUnit.MILLISECONDS));
            out.join(1000);
            err.join(1000);
            if (out.isAlive() || err.isAlive() || overflow.get()) {
                throw new IllegalStateException("Process unavailable");
            }
            return new Result(process.exitValue(), stdout.toString(StandardCharsets.UTF_8));
        } finally {
            process.descendants().forEach(children::add);
            children.forEach(ProcessHandle::destroyForcibly);
            // Clear interruption while waiting for cleanup; restore it afterwards.
            boolean interrupted = Thread.interrupted();
            try {
                process.waitFor(100, TimeUnit.MILLISECONDS);
                process.destroyForcibly();
                process.onExit().get(2, TimeUnit.SECONDS);
                for (var child : children) if (child.isAlive()) child.onExit().get(2, TimeUnit.SECONDS);
                process.getInputStream().close();
                process.getErrorStream().close();
                out.join(1000);
                err.join(1000);
            } catch (Exception e) {
                throw new UnsafeCleanupException();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    static final class UnsafeCleanupException extends Exception { }

    private static void drain(InputStream input, ByteArrayOutputStream output, AtomicBoolean overflow) {
        try (input) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 65536) { overflow.set(true); return; }
                if (output != null) output.write(buffer, 0, read);
            }
        } catch (Exception ignored) {
            overflow.set(true);
        }
    }
}
