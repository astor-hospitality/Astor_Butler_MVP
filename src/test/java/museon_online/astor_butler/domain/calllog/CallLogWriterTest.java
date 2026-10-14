package museon_online.astor_butler.domain.calllog;

import museon_online.astor_butler.model.ModelInteractionAuditRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
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
        writer.llm(record(true, false, Map.of()));

        assertThat(writer.enabled()).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void aFailingWriteIsSwallowed() {
        doThrow(new IllegalStateException("db down")).when(repository).append(any());
        CallLogWriter writer = writer(true, SALT);

        assertThatCode(() -> {
            writer.guestMessage("telegram", "1", null);
            writer.llm(record(true, false, Map.of("usage", Map.of("prompt_tokens", 1))));
        }).doesNotThrowAnyException();
    }

    @Test
    void anLlmCallCarriesTokensCostLatencyAndChannel() {
        CallLogWriter writer = writer(true, SALT);

        writer.llm(record(true, false, Map.of("usage", Map.of("prompt_tokens", 1200, "completion_tokens", 300, "total_tokens", 1500))));

        CallLogEntry entry = captured();
        assertThat(entry.callType()).isEqualTo("llm");
        assertThat(entry.name()).isEqualTo("GigaChat-3-Pro");
        assertThat(entry.result()).isEqualTo("ok");
        assertThat(entry.source()).isEqualTo("telegram");
        assertThat(entry.tokensIn()).isEqualTo(1200);
        assertThat(entry.tokensOut()).isEqualTo(300);
        assertThat(entry.costRub()).isEqualByComparingTo(new BigDecimal("0.1404"));
        assertThat(entry.latencyMs()).isEqualTo(850);
        assertThat(entry.pseudoUserId()).isEqualTo(writer.pseudonym("42"));
    }

    @Test
    void readsTheUsageNestedUnderTheModelResponseAndMarksFallbacksAndErrors() {
        CallLogWriter writer = writer(true, SALT);

        writer.llm(record(false, true, Map.of("modelResponse", Map.of("usage", Map.of("input_tokens", 10, "output_tokens", 5)))));
        CallLogEntry fallback = captured();
        assertThat(fallback.result()).isEqualTo("fallback");
        assertThat(fallback.errorCode()).isEqualTo("TimeoutException");
        assertThat(fallback.tokensIn()).isEqualTo(10);
        assertThat(fallback.tokensOut()).isEqualTo(5);
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

    static ModelInteractionAuditRecord record(boolean success, boolean fallback, Map<String, Object> metadata) {
        return new ModelInteractionAuditRecord("AERIS", "TELEGRAM", 7L, 42L, "corr", "scenario", "STATE", "purpose",
                "gigachat", "GigaChat-3-Pro", "", "prompt text", "guest text", "", "reply", success && !fallback, fallback, success,
                success ? "" : "TimeoutException", success ? "" : "slow", Duration.ofMillis(850), metadata);
    }
}
