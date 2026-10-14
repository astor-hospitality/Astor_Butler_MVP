package museon_online.astor_butler.capability;

import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.TurnChannel;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import museon_online.astor_butler.model.ModelTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CapabilityRegistryTest {

    private static final CapabilityContext CONCIERGE =
            new CapabilityContext("CONCIERGE", 501L, "c-1", 7L, null, "Анна", "+79120000060");

    private LedgerWriter ledger;
    private CapabilityProperties properties;

    @BeforeEach
    void setUp() {
        ledger = mock(LedgerWriter.class);
        properties = new CapabilityProperties();
        properties.setEnabled(true);
    }

    private CapabilityRegistry registry(CapabilityTool... tools) {
        return new CapabilityRegistry(List.of(tools), ledger, properties);
    }

    @Test
    void unknownToolIsNotFoundNotAnException() {
        CapabilityResult result = registry(new EchoTool()).invoke("book_spaceship", CONCIERGE, Map.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_FOUND);
    }

    @Test
    void toolExceptionIsUnavailable() {
        CapabilityResult result = registry(new FailingTool()).invoke("broken", CONCIERGE, Map.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(CapabilityResult.UNAVAILABLE);
        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, times(2)).write(drafts.capture());
        assertThat(drafts.getAllValues().get(0).kind()).isEqualTo(TurnKind.TOOL_CALL);
        VisitTurnDraft answer = drafts.getAllValues().get(1);
        assertThat(answer.kind()).isEqualTo(TurnKind.TOOL_RESULT);
        assertThat(answer.payload()).containsEntry("tool", "broken").containsEntry("ok", false)
                .containsEntry("error", CapabilityResult.UNAVAILABLE);
    }

    @Test
    void failingLedgerNeverHidesTheResult() {
        doThrow(new IllegalStateException("ledger is down")).when(ledger).write(any());

        CapabilityResult result = registry(new EchoTool()).invoke("echo", CONCIERGE, Map.of("partySize", 2));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("seen", 1);
        verify(ledger, times(2)).write(any());
    }

    @Test
    void nestedPhonesAreMaskedInTheLedger() {
        Map<String, Object> args = Map.of(
                "guest", Map.of("phone", "+79120000060"),
                "notes", List.of("позвоните +79120000060"));

        registry(new EchoTool()).invoke("echo", CONCIERGE, args);

        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, times(2)).write(drafts.capture());
        Map<String, Object> payload = drafts.getAllValues().get(0).payload();
        assertThat(payload.toString()).doesNotContainPattern("\\d{10}");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) payload.get("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> guest = (Map<String, Object>) arguments.get("guest");
        assertThat(guest.get("phone")).isIn(null, "+7 912 ***-**-60");
        assertThat(arguments.get("notes")).isEqualTo(List.of("позвоните +7 912 ***-**-60"));
    }

    @Test
    void everyCallIsWrittenToTheLedger() {
        CapabilityResult result = registry(new EchoTool()).invoke("echo", CONCIERGE,
                Map.of("partySize", 2, "guestPhone", "+7 912 000-00-60", "phone", "89120000060"));

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, times(2)).write(drafts.capture());
        VisitTurnDraft call = drafts.getAllValues().get(0);
        VisitTurnDraft answer = drafts.getAllValues().get(1);

        assertThat(call.kind()).isEqualTo(TurnKind.TOOL_CALL);
        assertThat(call.side()).isEqualTo(TurnSide.CONCIERGE);
        assertThat(call.channel()).isEqualTo(TurnChannel.INTERNAL);
        assertThat(call.visitId()).isEqualTo(7L);
        assertThat(call.sessionId()).isEqualTo("c-1");
        assertThat(call.text()).isEqualTo("echo");
        assertThat(call.goal()).isNull();
        assertThat(call.payload()).containsEntry("tool", "echo");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) call.payload().get("arguments");
        assertThat(arguments).containsEntry("partySize", 2);
        assertThat(arguments.toString()).doesNotContain("000-00", "0000060");

        assertThat(answer.kind()).isEqualTo(TurnKind.TOOL_RESULT);
        assertThat(answer.text()).isEqualTo("echo");
        assertThat(answer.payload()).containsEntry("tool", "echo").containsEntry("ok", true).containsEntry("error", null);
        assertThat(answer.payload()).containsKey("facts");
    }

    @Test
    void butlerAgentCallsAreWrittenAsTheButler() {
        CapabilityContext butler = new CapabilityContext("BUTLER_AGENT", 501L, null, null, null, null, null);

        registry(new EchoTool()).invoke("nope", butler, Map.of());

        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, times(2)).write(drafts.capture());
        assertThat(drafts.getAllValues()).allSatisfy(draft -> assertThat(draft.side()).isEqualTo(TurnSide.BUTLER));
        assertThat(drafts.getAllValues().get(1).payload()).containsEntry("error", CapabilityResult.NOT_FOUND);
    }

    @Test
    void disabledRegistryAnswersUnavailable() {
        properties.setEnabled(false);
        CapabilityRegistry registry = registry(new EchoTool());

        CapabilityResult result = registry.invoke("echo", CONCIERGE, Map.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(CapabilityResult.UNAVAILABLE);
        assertThat(registry.asModelTools()).isEmpty();
        assertThat(registry.tools()).isEmpty();
        verifyNoInteractions(ledger);
    }

    @Test
    void toolsAreOfferedToTheModel() {
        List<ModelTool> tools = registry(new EchoTool()).asModelTools();

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("echo");
            assertThat(tool.parameters()).containsEntry("type", "object");
        });
    }

    private static final class EchoTool implements CapabilityTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Повторяет аргументы"; }
        @Override public Map<String, Object> parameters() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public boolean commitment() { return false; }
        @Override public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
            return CapabilityResult.ok(Map.of("seen", args.size()));
        }
    }

    private static final class FailingTool implements CapabilityTool {
        @Override public String name() { return "broken"; }
        @Override public String description() { return "Всегда падает"; }
        @Override public Map<String, Object> parameters() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public boolean commitment() { return false; }
        @Override public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
            throw new IllegalStateException("database is gone");
        }
    }
}
