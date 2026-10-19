package museon_online.astor_butler.domain.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Concierge session's turns never stay out of its visit (16.10: 34 turns of a session had no visit because the
 * booking's visit was opened after the booking): the real ledger on H2, the guest journey stubbed.
 */
class LedgerSessionVisitTest {

    private static final Instant NOW = Instant.parse("2026-10-16T08:30:00Z");
    private static final String SESSION = "c-yana";

    private LedgerTestDatabase database;
    private VisitTurnRepository turns;
    private final GuestVisitRepository visits = mock(GuestVisitRepository.class);
    private LedgerWriter ledger;

    @BeforeEach
    void setUp() {
        database = LedgerTestDatabase.create();
        turns = new VisitTurnRepository(database.jdbc(), new ObjectMapper());
        LedgerProperties properties = new LedgerProperties();
        properties.setEnabled(true);
        when(visits.findByReservation(anyLong())).thenReturn(Optional.empty());
        ledger = new LedgerWriter(turns, visits, properties, mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private void say(String text) {
        ledger.write(new VisitTurnDraft(null, SESSION, TurnSide.CONCIERGE, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, text, null, null));
    }

    private void booked(long reservationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "reservation_create");
        payload.put("ok", true);
        payload.put("error", null);
        payload.put("facts", Map.of("reservationId", reservationId, "status", "AWAITING_HOSTESS"));
        ledger.write(new VisitTurnDraft(null, SESSION, TurnSide.CONCIERGE, TurnChannel.INTERNAL, TurnKind.TOOL_RESULT,
                "reservation_create", payload, null));
    }

    private void visitOpened(long visitId, long reservationId) {
        when(visits.findByReservation(reservationId)).thenReturn(Optional.of(new GuestVisit(visitId, reservationId, null, null,
                "AERIS", JourneyStage.REQUESTED, null, null, 0, NOW, NOW, null)));
    }

    @Test
    void theSessionsTurnsMoveIntoTheVisitOfItsBookingOpenedLater() {
        say("Ищу стол на двоих на вечер");
        booked(68L);
        say("Гость подтвердил");
        assertThat(turns.ofSession(SESSION)).extracting(VisitTurn::visitId).containsOnlyNulls();

        visitOpened(64L, 68L);
        say("Гость спрашивает про парковку");

        assertThat(turns.ofSession(SESSION)).hasSize(4).extracting(VisitTurn::visitId).containsOnly(64L);
        assertThat(turns.ofVisit(64L)).hasSize(4);
    }

    @Test
    void aSessionAlreadyLinkedWritesItsLaterTurnsIntoTheVisit() {
        say("Ищу стол");
        ledger.linkSession(SESSION, 64L);

        say("Ещё вопрос гостя");
        booked(68L);

        assertThat(turns.ofSession(SESSION)).hasSize(3).extracting(VisitTurn::visitId).containsOnly(64L);
    }

    @Test
    void aFailedOrForeignBookingLinksNothing() {
        Map<String, Object> refused = new LinkedHashMap<>();
        refused.put("tool", "reservation_create");
        refused.put("ok", false);
        refused.put("facts", Map.of("reservationId", 68L, "reason", "overlapping"));
        ledger.write(new VisitTurnDraft(null, SESSION, TurnSide.CONCIERGE, TurnChannel.INTERNAL, TurnKind.TOOL_RESULT,
                "reservation_create", refused, null));
        visitOpened(64L, 68L);

        say("Гость думает");

        assertThat(turns.reservationsBookedBy(SESSION)).isEmpty();
        assertThat(turns.ofSession(SESSION)).extracting(VisitTurn::visitId).containsOnlyNulls();
        assertThat(ledger.visitBookedBy("c-other")).isEmpty();
    }
}
