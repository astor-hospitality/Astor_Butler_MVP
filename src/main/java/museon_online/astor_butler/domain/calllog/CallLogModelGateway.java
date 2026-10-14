package museon_online.astor_butler.domain.calllog;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.model.ModelEmbeddingRequest;
import museon_online.astor_butler.model.ModelEmbeddingResponse;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.model.ModelVisionRequest;
import museon_online.astor_butler.model.ModelVisionResponse;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Counts every model call of the application's gateway bean once, whoever makes it: text ({@code llm}), photo
 * ({@code llm:vision}) and embedding ({@code llm:embedding}). The answer and any exception pass through untouched;
 * the call log can never change or fail a model call. Installed by {@link CallLogModelGatewayPostProcessor}.
 */
@Slf4j
public class CallLogModelGateway implements ModelGateway {

    private final ModelGateway delegate;
    private final Supplier<CallLogWriter> writer;

    public CallLogModelGateway(ModelGateway delegate, Supplier<CallLogWriter> writer) {
        this.delegate = delegate;
        this.writer = writer;
    }

    public ModelGateway delegate() {
        return delegate;
    }

    @Override
    public ModelTextResponse generateText(ModelTextRequest request) {
        long started = System.nanoTime();
        ModelTextResponse response;
        try {
            response = delegate.generateText(request);
        } catch (RuntimeException e) {
            failed(CallLogEntry.TYPE_LLM, null, e, started);
            throw e;
        }
        if (response != null) {
            answered(CallLogEntry.TYPE_LLM, name(response.model(), response.provider()), response.fallback(), response.metadata(), started);
        }
        return response;
    }

    @Override
    public ModelEmbeddingResponse generateEmbedding(ModelEmbeddingRequest request) {
        long started = System.nanoTime();
        ModelEmbeddingResponse response;
        try {
            response = delegate.generateEmbedding(request);
        } catch (RuntimeException e) {
            failed(CallLogEntry.TYPE_LLM_EMBEDDING, request == null ? null : request.model(), e, started);
            throw e;
        }
        if (response != null) {
            answered(CallLogEntry.TYPE_LLM_EMBEDDING, name(response.model(), response.provider()), response.fallback(), response.metadata(), started);
        }
        return response;
    }

    @Override
    public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
        long started = System.nanoTime();
        ModelVisionResponse response;
        try {
            response = delegate.analyzeImage(request);
        } catch (RuntimeException e) {
            failed(CallLogEntry.TYPE_LLM_VISION, request == null ? null : request.model(), e, started);
            throw e;
        }
        if (response != null) {
            answered(CallLogEntry.TYPE_LLM_VISION, name(response.model(), response.provider()), response.fallback(), response.metadata(), started);
        }
        return response;
    }

    void answered(String type, String model, boolean fallback, Map<String, Object> metadata, long started) {
        try {
            Map<?, ?> usage = CallLogWriter.usage(metadata);
            writer.get().llmCall(type, model, fallback ? "fallback" : "ok", null,
                    CallLogWriter.integer(usage, "prompt_tokens", "input_tokens"),
                    CallLogWriter.integer(usage, "completion_tokens", "output_tokens"),
                    elapsed(started));
        } catch (RuntimeException e) {
            log.warn("Call log model row was not written: {}", e.getClass().getSimpleName());
        }
    }

    void failed(String type, String model, RuntimeException error, long started) {
        try {
            writer.get().llmCall(type, model, "error", rootCause(error), null, null, elapsed(started));
        } catch (RuntimeException e) {
            log.warn("Call log model row was not written: {}", e.getClass().getSimpleName());
        }
    }

    /** The class of the deepest cause: wrappers such as CompletionException or a bare RuntimeException say nothing. */
    static String rootCause(Throwable error) {
        Throwable cause = error;
        int depth = 0;
        while (cause.getCause() != null && cause.getCause() != cause && depth++ < 16) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private static String name(String model, String provider) {
        return model == null || model.isBlank() ? provider : model;
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
