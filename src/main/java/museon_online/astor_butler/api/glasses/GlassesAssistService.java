package museon_online.astor_butler.api.glasses;

import jakarta.annotation.PreDestroy;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.*;

@Service
public class GlassesAssistService {
    private final ModelGateway gateway;
    private final boolean textEnabled;
    private final long timeoutMs;
    // No queue: a stuck provider cannot create an unbounded backlog, even after client timeout.
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "glasses-assist");
                t.setDaemon(true);
                return t;
            });
    private volatile Instant textReadyUntil = Instant.MIN;
    private long rateWindow;
    private int requests;

    public GlassesAssistService(ModelGateway gateway,
                                @Value("${astor.glasses.text-enabled:false}") boolean textEnabled,
                                @Value("${astor.glasses.timeout-ms:10000}") long timeoutMs) {
        this.gateway = gateway;
        this.textEnabled = textEnabled;
        this.timeoutMs = Math.max(1, Math.min(timeoutMs, 30000));
    }

    public record Capabilities(boolean text, boolean voice, boolean vision, int maxAudioSeconds,
                               int maxAudioBytes, int maxImageBytes, int maxImageDimension, int maxTextChars,
                               int maxBodyBytes) { }

    Capabilities capabilities() {
        // A switch is permission to attempt text, not proof that a provider is ready.
        return new Capabilities(textEnabled && Instant.now().isBefore(textReadyUntil), false, false,
                30, 2097152, 2097152, 1280, 4000, 5242880);
    }

    synchronized void checkRate() {
        long window = System.currentTimeMillis() / 60000;
        if (window != rateWindow) {
            rateWindow = window;
            requests = 0;
        }
        if (++requests > 10) throw new GlassesFailure(429, "RATE_LIMITED", "Pilot request limit reached");
    }

    String assist(String text) {
        if (!textEnabled) throw unavailable();
        Future<String> future;
        try {
            future = executor.submit(() -> {
                var response = gateway.generateText(ModelTextRequest.of(
                        "Ты информационный помощник сотрудника ресторана. Отвечай кратко по-русски для озвучивания. "
                                + "Если нет данных заведения, скажи об этом. Не утверждай, что принял задачу, изменил заказ "
                                + "или завершил действие. Не выдумывай меню, столы, назначения и статусы. Вопрос сотрудника:\n" + text,
                        "GLASSES_INFORMATIONAL", "READ_ONLY", "staff-informational"));
                if (response == null || response.fallback() || response.text() == null || response.text().isBlank()) {
                    throw unavailable();
                }
                return response.text().trim();
            });
        } catch (RejectedExecutionException e) {
            throw new GlassesFailure(429, "BUSY", "Assistant is busy");
        }
        try {
            String result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            textReadyUntil = Instant.now().plusSeconds(60);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            textReadyUntil = Instant.MIN;
            future.cancel(true);
            throw unavailable();
        } catch (ExecutionException | TimeoutException e) {
            textReadyUntil = Instant.MIN;
            future.cancel(true);
            throw unavailable();
        }
    }

    private GlassesFailure unavailable() {
        return new GlassesFailure(503, "TEXT_UNAVAILABLE", "Text provider unavailable or not configured");
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
