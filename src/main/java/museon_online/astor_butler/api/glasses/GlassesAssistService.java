package museon_online.astor_butler.api.glasses;

import jakarta.annotation.PreDestroy;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelVisionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.*;

@Service
public class GlassesAssistService {
    private final ModelGateway gateway;
    private final GlassesVoice voice;
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
    private volatile Instant visionReadyUntil = Instant.MIN;
    private long rateWindow;
    private int requests;

    @Autowired
    public GlassesAssistService(ModelGateway gateway, GlassesVoice voice,
                                @Value("${astor.glasses.text-enabled:false}") boolean textEnabled,
                                @Value("${astor.glasses.timeout-ms:10000}") long timeoutMs) {
        this.gateway = gateway;
        this.voice = voice;
        this.textEnabled = textEnabled;
        this.timeoutMs = Math.max(1, Math.min(timeoutMs, 45000));
    }

    public GlassesAssistService(ModelGateway gateway, boolean enabled, long timeoutMs) {
        this(gateway, new GlassesVoice(false, "python3", "", "", "/tmp/astor-glasses", 20000), enabled, timeoutMs);
    }

    public record Capabilities(boolean text, boolean voice, boolean vision, int maxAudioSeconds,
                               int maxAudioBytes, int maxImageBytes, int maxImageDimension, int maxTextChars,
                               int maxBodyBytes) { }

    Capabilities capabilities() {
        // A switch is permission to attempt text, not proof that a provider is ready.
        boolean textReady = textEnabled && Instant.now().isBefore(textReadyUntil);
        return new Capabilities(textReady, textReady && voice.ready(), Instant.now().isBefore(visionReadyUntil),
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
        return execute(() -> generate(text), false);
    }

    String assistAudio(byte[] audio) {
        return execute(() -> generate(voice.transcribe(audio)), false);
    }

    String assistImage(String prompt, String imageBase64) {
        return execute(() -> {
            var response = gateway.analyzeImage(ModelVisionRequest.of(
                    "Кратко по-русски опиши только видимое на фото. Если детали неразличимы, скажи об этом. "
                            + "Не определяй личности, заказ, аллергии или оплату; не утверждай выполнение действий. " + prompt,
                    imageBase64, "image/jpeg", null, "GLASSES_INFORMATIONAL", "READ_ONLY", "staff-vision"));
            if (response == null || response.fallback() || response.text() == null || response.text().isBlank()) {
                throw new GlassesFailure(503, "VISION_UNAVAILABLE", "Vision provider unavailable or not configured");
            }
            return response.text().trim();
        }, true);
    }

    private String generate(String text) {
        if (!textEnabled) throw unavailable();
        var response = gateway.generateText(ModelTextRequest.of(
                "Ты информационный помощник сотрудника ресторана. Ответь максимум тремя короткими предложениями по-русски, без списков, для озвучивания. "
                        + "Если нет данных заведения, скажи об этом. Не утверждай, что принял задачу, изменил заказ "
                        + "или завершил действие. Не выдумывай меню, столы, назначения и статусы. Вопрос сотрудника:\n" + text,
                "GLASSES_INFORMATIONAL", "READ_ONLY", "staff-informational"));
        if (response == null || response.fallback() || response.text() == null || response.text().isBlank()) {
            throw unavailable();
        }
        return response.text().trim();
    }

    private String execute(Callable<String> call, boolean image) {
        Future<String> future;
        try {
            future = executor.submit(call);
        } catch (RejectedExecutionException e) {
            throw new GlassesFailure(429, "BUSY", "Assistant is busy");
        }
        try {
            String result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (image) visionReadyUntil = Instant.now().plusSeconds(300);
            else textReadyUntil = Instant.now().plusSeconds(300);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            clearReadiness(image);
            throw unavailable();
        } catch (ExecutionException | TimeoutException e) {
            future.cancel(true);
            clearReadiness(image);
            if (e.getCause() instanceof GlassesFailure failure) throw failure;
            throw new GlassesFailure(503, image ? "VISION_UNAVAILABLE" : "TEXT_UNAVAILABLE", "Assistant provider unavailable");
        }
    }

    private void clearReadiness(boolean image) {
        if (image) visionReadyUntil = Instant.MIN;
        else textReadyUntil = Instant.MIN;
    }

    private GlassesFailure unavailable() {
        return new GlassesFailure(503, "TEXT_UNAVAILABLE", "Text provider unavailable or not configured");
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
