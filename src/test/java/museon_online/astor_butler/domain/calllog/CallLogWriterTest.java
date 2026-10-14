package museon_online.astor_butler.domain.calllog;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CallLogWriterTest {

    private static final String SALT = "a-long-enough-test-salt";

    private final CallLogRepository repository = mock(CallLogRepository.class);

    @Test
    void theSameIdGetsTheSamePseudonymAndTheRawIdIsNotInIt() {
        CallLogWriter writer = writer(true, SALT);

        String first = writer.pseudonym("1773317437");

        assertThat(first).hasSize(24).matches("[0-9a-f]{24}").doesNotContain("1773317437");
        assertThat(writer.pseudonym("1773317437")).isEqualTo(first);
        assertThat(writer.pseudonym("1773317438")).isNotEqualTo(first);
        assertThat(writer(true, SALT + "x").pseudonym("1773317437")).isNotEqualTo(first);
    }

    @Test
    void aShortSaltWritesNoIds() {
        CallLogWriter writer = writer(true, "short");

        writer.guestMessage("telegram", "1773317437", "corr");

        assertThat(writer.pseudonym("1773317437")).isNull();
        assertThat(captured().pseudoUserId()).isNull();
    }

    @Test
    void offWritesNothing() {
        CallLogWriter writer = writer(false, SALT);

        writer.guestMessage("telegram", "1", null);
        writer.tool("saby:/order", "OK", null, 10L);
        writer.targetAction("bill:open", "ESTIMATED", "direct", "1");
        writer.llmCall("llm", "GigaChat", "ok", null, 1, 1, 5L);

        assertThat(writer.enabled()).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void aFailingWriteIsSwallowed() {
        doThrow(new IllegalStateException("db down")).when(repository).append(any());
        CallLogWriter writer = writer(true, SALT);

        assertThatCode(() -> {
            writer.guestMessage("telegram", "1", null);
            writer.llmCall("llm", "GigaChat", "ok", null, 1, 1, 5L);
        }).doesNotThrowAnyException();
    }

    @Test
    void anLlmCallCarriesTokensCostLatencyAndTheCallerOfTheMessage() {
        CallLogWriter writer = writer(true, SALT);

        try (CallLogContext.Scope ignored = CallLogContext.open("TELEGRAM", "42", "corr")) {
            writer.llmCall("llm", "GigaChat-3-Pro", "ok", null, 1200, 300, 850L);
        }

        CallLogEntry entry = captured();
        assertThat(entry.callType()).isEqualTo("llm");
        assertThat(entry.name()).isEqualTo("GigaChat-3-Pro");
        assertThat(entry.source()).isEqualTo("telegram");
        assertThat(entry.correlationId()).isEqualTo("corr");
        assertThat(entry.costRub()).isEqualByComparingTo(new BigDecimal("0.1404"));
        assertThat(entry.latencyMs()).isEqualTo(850);
        assertThat(entry.pseudoUserId()).isEqualTo(writer.pseudonym("42"));
    }

    @Test
    void noUsageMeansNullTokensAndNullCost() {
        CallLogWriter writer = writer(true, SALT);

        writer.llmCall("llm", "GigaChat-3-Pro", "fallback", null, null, null, 10L);

        CallLogEntry entry = captured();
        assertThat(entry.tokensIn()).isNull();
        assertThat(entry.tokensOut()).isNull();
        assertThat(entry.costRub()).isNull();
        assertThat(entry.source()).isEqualTo("internal");
    }

    @Test
    void readsTheUsageOfAnAnswerOrOfAnAuditedModelResponse() {
        assertThat(CallLogWriter.integer(CallLogWriter.usage(Map.of("usage", Map.of("prompt_tokens", 3))), "prompt_tokens")).isEqualTo(3);
        assertThat(CallLogWriter.integer(CallLogWriter.usage(Map.of("modelResponse", Map.of("usage", Map.of("input_tokens", 10)))),
                "prompt_tokens", "input_tokens")).isEqualTo(10);
        assertThat(CallLogWriter.integer(Map.of("output_tokens", "5"), "completion_tokens", "output_tokens")).isEqualTo(5);
        assertThat(CallLogWriter.usage(null).isEmpty()).isTrue();
    }

    private CallLogEntry captured() {
        ArgumentCaptor<CallLogEntry> captor = ArgumentCaptor.forClass(CallLogEntry.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        return captor.getValue();
    }

    private CallLogWriter writer(boolean enabled, String salt) {
        CallLogProperties properties = new CallLogProperties();
        properties.setEnabled(enabled);
        properties.setSalt(salt);
        return new CallLogWriter(properties, repository, new ModelTariff());
    }
}
