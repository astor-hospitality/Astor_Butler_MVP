package museon_online.astor_butler.domain.calllog;

import museon_online.astor_butler.model.ModelCapability;
import museon_online.astor_butler.model.ModelEmbeddingRequest;
import museon_online.astor_butler.model.ModelEmbeddingResponse;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.model.ModelVisionRequest;
import museon_online.astor_butler.model.ModelVisionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Every model call through the gateway bean is counted once, whoever calls it (greeting, summarizer, glasses, warmup…). */
class CallLogModelGatewayTest {

    private static final String SALT = "a-long-enough-test-salt";

    private final ModelGateway delegate = mock(ModelGateway.class);
    private final CallLogRepository repository = mock(CallLogRepository.class);
    private final CallLogWriter writer = writer(true);
    private final CallLogModelGateway gateway = new CallLogModelGateway(delegate, () -> writer);

    @Test
    void aDirectTextCallFromANonAuditedCallerIsCountedOnceWithTokensAndCost() {
        ModelTextRequest request = ModelTextRequest.of("prompt", "Greeting", "START", "greeting");
        ModelTextResponse response = new ModelTextResponse("Добрый день", "gigachat", "GigaChat-3-Pro", ModelCapability.TEXT_GENERATION,
                Duration.ofMillis(800), false, Map.of("usage", Map.of("prompt_tokens", 1200, "completion_tokens", 300)));
        when(delegate.generateText(request)).thenReturn(response);

        assertThat(gateway.generateText(request)).isSameAs(response);

        CallLogEntry entry = single();
        assertThat(entry.callType()).isEqualTo("llm");
        assertThat(entry.name()).isEqualTo("GigaChat-3-Pro");
        assertThat(entry.result()).isEqualTo("ok");
        assertThat(entry.tokensIn()).isEqualTo(1200);
        assertThat(entry.tokensOut()).isEqualTo(300);
        assertThat(entry.costRub()).isEqualByComparingTo(new BigDecimal("0.1404"));
        assertThat(entry.latencyMs()).isNotNull();
        assertThat(entry.source()).isEqualTo("internal");
        assertThat(entry.pseudoUserId()).isNull();
    }

    @Test
    void aCallInsideAGuestMessageCarriesItsChannelUserAndCorrelationEvenAcrossAnAsyncHop() {
        when(delegate.generateText(any())).thenReturn(ModelTextResponse.text("ok", "gigachat", "GigaChat", Duration.ZERO));

        try (CallLogContext.Scope ignored = CallLogContext.open("TELEGRAM", "42", "corr-1")) {
            CompletableFuture.supplyAsync(CallLogContext.propagate(() -> gateway.generateText(ModelTextRequest.of("p", "s", "S", "x")))).join();
        }
        gateway.generateText(ModelTextRequest.of("p", "s", "S", "x"));

        ArgumentCaptor<CallLogEntry> captor = ArgumentCaptor.forClass(CallLogEntry.class);
        verify(repository, times(2)).append(captor.capture());
        CallLogEntry inMessage = captor.getAllValues().get(0);
        assertThat(inMessage.source()).isEqualTo("telegram");
        assertThat(inMessage.correlationId()).isEqualTo("corr-1");
        assertThat(inMessage.pseudoUserId()).isEqualTo(writer.pseudonym("42"));
        CallLogEntry after = captor.getAllValues().get(1);
        assertThat(after.source()).isEqualTo("internal");
        assertThat(after.correlationId()).isNull();
    }

    @Test
    void visionAndEmbeddingCallsHaveTheirOwnTypes() {
        when(delegate.analyzeImage(any())).thenReturn(ModelVisionResponse.vision("a plate", "gigachat", "GigaChat-2-Max", Duration.ZERO));
        when(delegate.generateEmbedding(any())).thenReturn(ModelEmbeddingResponse.embedding(List.of(0.1), "gigachat", "Embeddings", Duration.ZERO));

        gateway.analyzeImage(ModelVisionRequest.of("p", "aW1n", "image/jpeg", "GigaChat-2-Max", "s", "S", "x"));
        gateway.generateEmbedding(ModelEmbeddingRequest.of("text", "Embeddings", "s", "S", "x"));

        ArgumentCaptor<CallLogEntry> captor = ArgumentCaptor.forClass(CallLogEntry.class);
        verify(repository, times(2)).append(captor.capture());
        assertThat(captor.getAllValues()).extracting(CallLogEntry::callType, CallLogEntry::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("llm:vision", "GigaChat-2-Max"),
                        org.assertj.core.groups.Tuple.tuple("llm:embedding", "Embeddings"));
    }

    @Test
    void aFailedCallIsAnErrorWithTheRootCauseAndStillThrows() {
        RuntimeException failure = new IllegalStateException("wrapped", new RuntimeException(new SocketTimeoutException("slow")));
        when(delegate.generateText(any())).thenThrow(failure);

        assertThatThrownBy(() -> gateway.generateText(ModelTextRequest.of("p", "s", "S", "x"))).isSameAs(failure);

        CallLogEntry entry = single();
        assertThat(entry.result()).isEqualTo("error");
        assertThat(entry.errorCode()).isEqualTo("SocketTimeoutException");
        assertThat(entry.tokensIn()).isNull();
    }

    @Test
    void aFallbackAnswerIsAFallbackAndNoUsageMeansNoTokensAndNoCost() {
        when(delegate.generateText(any())).thenReturn(new ModelTextResponse("canned", "gigachat", "GigaChat-3-Pro",
                ModelCapability.TEXT_GENERATION, Duration.ZERO, true, Map.of()));

        gateway.generateText(ModelTextRequest.of("p", "s", "S", "x"));

        CallLogEntry entry = single();
        assertThat(entry.result()).isEqualTo("fallback");
        assertThat(entry.tokensIn()).isNull();
        assertThat(entry.tokensOut()).isNull();
        assertThat(entry.costRub()).isNull();
    }

    @Test
    void aBrokenCallLogNeverBreaksTheModelCall() {
        CallLogModelGateway broken = new CallLogModelGateway(delegate, () -> {
            throw new IllegalStateException("no writer");
        });
        ModelTextResponse response = ModelTextResponse.text("ok", "gigachat", "GigaChat", Duration.ZERO);
        when(delegate.generateText(any())).thenReturn(response);

        assertThat(broken.generateText(ModelTextRequest.of("p", "s", "S", "x"))).isSameAs(response);
    }

    @Test
    void offNothingIsWritten() {
        CallLogModelGateway off = new CallLogModelGateway(delegate, () -> writer(false));
        when(delegate.generateText(any())).thenReturn(ModelTextResponse.text("ok", "gigachat", "GigaChat", Duration.ZERO));

        off.generateText(ModelTextRequest.of("p", "s", "S", "x"));

        verifyNoInteractions(repository);
    }

    @Test
    void thePostProcessorWrapsModelGatewayBeansOnlyAndOnlyOnce() {
        CallLogModelGatewayPostProcessor processor = new CallLogModelGatewayPostProcessor(() -> writer);

        Object wrapped = processor.postProcessAfterInitialization(delegate, "gigaChatModelGateway");
        Object other = processor.postProcessAfterInitialization("not a gateway", "other");

        assertThat(wrapped).isInstanceOf(CallLogModelGateway.class);
        assertThat(((CallLogModelGateway) wrapped).delegate()).isSameAs(delegate);
        assertThat(processor.postProcessAfterInitialization(wrapped, "again")).isSameAs(wrapped);
        assertThat(other).isEqualTo("not a gateway");
    }

    private CallLogEntry single() {
        ArgumentCaptor<CallLogEntry> captor = ArgumentCaptor.forClass(CallLogEntry.class);
        verify(repository, atLeastOnce()).append(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        return captor.getValue();
    }

    private CallLogWriter writer(boolean enabled) {
        CallLogProperties properties = new CallLogProperties();
        properties.setEnabled(enabled);
        properties.setSalt(SALT);
        return new CallLogWriter(properties, repository, new ModelTariff());
    }
}
