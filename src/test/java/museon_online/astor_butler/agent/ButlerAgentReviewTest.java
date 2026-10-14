package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.IdempotencyStore;
import museon_online.astor_butler.capability.tools.CapabilityFixtures;
import museon_online.astor_butler.capability.tools.ReservationAccess;
import museon_online.astor_butler.capability.tools.ReviewSubmitTool;
import museon_online.astor_butler.domain.billing.VisitReviewService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelToolCall;
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The REVIEW hand-off: the Concierge passes the guest's rating, the agent stores it through review_submit. */
class ButlerAgentReviewTest {

    @Test
    void theGuestsRatingReachesTheModelAndIsStored() {
        List<ModelChatRequest> requests = new ArrayList<>();
        ToolCallingModel model = request -> {
            requests.add(request);
            return requests.size() == 1
                    ? new ModelChatResponse("", Optional.of(new ModelToolCall("review_submit", Map.of("reservationId", 41, "stars", 5))),
                    "gigachat", request.model(), Duration.ZERO)
                    : new ModelChatResponse("Спасибо за оценку!", Optional.empty(), "gigachat", request.model(), Duration.ZERO);
        };
        TableReservationRepository orders = mock(TableReservationRepository.class);
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, null, TableReservationStatus.SEATED)));
        GuestVisitRepository visits = mock(GuestVisitRepository.class);
        when(visits.findByReservation(41L)).thenReturn(Optional.of(CapabilityFixtures.visit(42L, 41L, null, JourneyStage.AFTER)));
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.existsLink(42L, "c-1")).thenReturn(true);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.of(42L));
        VisitReviewService reviews = mock(VisitReviewService.class);
        when(reviews.submit(41L, 5, null)).thenReturn(true);
        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        LedgerWriter ledger = mock(LedgerWriter.class);
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(new ReviewSubmitTool(reviews, new ReservationAccess(orders, visits, turns))),
                ledger, capabilities, mock(IdempotencyStore.class));
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        ButlerAgent agent = new ButlerAgent(new AgentLoop(model, registry, properties), registry, properties,
                mock(AgentLessonRepository.class), mock(GuestMemory.class),
                new VenueFactsCatalog(new VenueFacts(null, null, null, null, null)), capabilities, ledger, turns, visits);

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("review", Map.of("stars", 5, "comment", "Всё было вкусно"));
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("reservationId", 41);
        pending.put("billId", 77);
        position.put("pendingReview", pending);
        NegotiationReply reply = agent.negotiate(new NegotiationTurn("c-1", null, "5", position, "Мария", null,
                "Гостья ставит пятёрку.", "t-9", true));

        assertThat(reply.fallback()).isFalse();
        List<ModelChatMessage> seen = requests.get(0).messages();
        assertThat(seen.get(0).content()).contains("review_submit").contains("pendingReview");
        assertThat(seen.get(seen.size() - 1).content())
                .contains("оценка гостя: 5 (Всё было вкусно)")
                .contains("ждём оценку: бронь 41, счёт 77");
        verify(reviews).submit(41L, 5, null);
        assertThat(reply.facts()).singleElement().satisfies(fact -> assertThat(fact).containsEntry("tool", "review_submit")
                .containsEntry("ok", true));
    }

    @Test
    void aRatingWithoutCommentAndAPendingReviewWithoutBill() {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("time", "13:00");
        position.put("review", Map.of("stars", 4.0));
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("reservationId", 41);
        pending.put("billId", null);
        position.put("pendingReview", pending);

        assertThat(ButlerAgent.position(position)).contains("13:00").contains("оценка гостя: 4")
                .doesNotContain("4 (").contains("ждём оценку: бронь 41").doesNotContain("счёт");
    }
}
