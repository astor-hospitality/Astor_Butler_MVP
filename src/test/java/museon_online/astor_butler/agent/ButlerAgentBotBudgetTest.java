package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ToolCallingModel;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Butler's own bot has one update thread: the agent may hold it for the bot budget only, then the scenarios answer. */
class ButlerAgentBotBudgetTest {

    @Test
    void aSlowModelInTheBotFallsBackWithinTheBotBudget() {
        ToolCallingModel slow = request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ModelChatResponse("Поздно.", Optional.empty(), "gigachat", request.model(), Duration.ZERO);
        };
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setAllowlist("501");
        properties.setBotTurnTimeoutMs(300);
        CapabilityProperties capabilities = new CapabilityProperties();
        CapabilityRegistry registry = new CapabilityRegistry(List.of(), mock(LedgerWriter.class), capabilities);
        GuestVisitRepository visits = mock(GuestVisitRepository.class);
        when(visits.activeByChat(anyLong(), any())).thenReturn(Optional.empty());
        ButlerAgent agent = new ButlerAgent(new AgentLoop(slow, registry, properties), registry, properties,
                mock(AgentLessonRepository.class), mock(GuestMemory.class),
                new VenueFactsCatalog(new VenueFacts(null, null, null, null, null)), capabilities, mock(LedgerWriter.class),
                mock(VisitTurnRepository.class), visits);

        long started = System.nanoTime();
        Optional<String> reply = agent.reply(IncomingMessage.telegram(501L, 501L, 1, 100, "Стол на вечер", null, "Мария",
                null, "maria", "ru", false, "c"), null);
        long tookMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(reply).isEmpty();
        assertThat(tookMs).isLessThan(2_000);
    }

    @Test
    void theBotBudgetDefaultsToTwelveSeconds() {
        assertThat(new AgentProperties().getBotTurnTimeoutMs()).isEqualTo(12_000);
    }
}
