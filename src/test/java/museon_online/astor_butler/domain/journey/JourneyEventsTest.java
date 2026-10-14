package museon_online.astor_butler.domain.journey;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyEventsTest {

    private static final long CHAT = 421441838L;
    private static final Instant VISIT = Instant.parse("2026-10-14T08:00:00Z");

    private JourneyTestDatabase database;
    private GuestVisitRepository repository;
    private JourneyEvents events;

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        repository = new GuestVisitRepository(database.jdbc());
        GuestJourneyProperties properties = new GuestJourneyProperties();
        properties.setEnabled(true);
        JourneyRecorder recorder = new JourneyRecorder(repository, properties, Clock.fixed(VISIT, ZoneOffset.UTC));
        events = new JourneyEvents(recorder, repository, Clock.fixed(VISIT, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private static TableReservationOrder order(long id) {
        return order(id, VISIT);
    }

    private static TableReservationOrder order(long id, Instant start) {
        return new TableReservationOrder(id, CHAT, CHAT, null, 5L, "13", "Стол 13", null, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                "TELEGRAM", start, start.plusSeconds(5400), 2, "Мария", "+79120000000", null, null, null, null, null,
                VISIT.minusSeconds(86400), VISIT.minusSeconds(86400), null);
    }

    @Test
    void reservationEventOpensTheVisitWithGuestData() {
        events.onReservation(order(42L), VisitEventKind.REQUESTED, JourneyEvents.BOT);

        GuestVisit visit = repository.findByReservation(42L).orElseThrow();
        assertThat(visit.chatId()).isEqualTo(CHAT);
        assertThat(visit.guestPhone()).isEqualTo("+79120000000");
        assertThat(visit.visitDay()).isEqualTo(VISIT);
        assertThat(visit.stage()).isEqualTo(JourneyStage.REQUESTED);
    }

    @Test
    void eventByReservationIdNeedsAnOpenVisit() {
        events.onReservationId(77L, VisitEventKind.BILL_REQUESTED, JourneyEvents.GUEST);
        assertThat(repository.findByReservation(77L)).isEmpty();

        events.onReservation(order(77L), VisitEventKind.REQUESTED, JourneyEvents.BOT);
        events.onReservationId(77L, VisitEventKind.BILL_REQUESTED, JourneyEvents.GUEST);
        assertThat(repository.findByReservation(77L).orElseThrow().stage()).isEqualTo(JourneyStage.BILL);
    }

    @Test
    void chatEventGoesToTheActiveVisit() {
        events.onReservation(order(42L), VisitEventKind.REQUESTED, JourneyEvents.BOT);

        events.onChat(CHAT, VisitEventKind.DISH_QUESTION, JourneyEvents.GUEST, "калории пасты");

        assertThat(database.jdbc().queryForObject(
                "SELECT note FROM guest_visit_events WHERE kind = 'DISH_QUESTION'", String.class)).isEqualTo("калории пасты");
    }

    @Test
    void chatEventWithoutVisitIsDropped() {
        events.onChat(CHAT, VisitEventKind.GOOD_DEED, JourneyEvents.GUEST, null);

        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM guest_visit_events", Integer.class)).isZero();
    }

    @Test
    void guestMessagesCountOnlyInPrivateChats() {
        events.onReservation(order(42L), VisitEventKind.REQUESTED, JourneyEvents.BOT);

        events.guestMessage(CHAT);
        events.guestMessage(-1002768239841L);
        events.guestMessage(null);

        assertThat(repository.findByReservation(42L).orElseThrow().guestMessages()).isEqualTo(1);
    }

    @Test
    void aMovedBookingMovesTheVisitDay() {
        events.onReservation(order(42L), VisitEventKind.REQUESTED, JourneyEvents.BOT);
        Instant friday = VISIT.plusSeconds(2 * 86_400);
        TableReservationOrder moved = order(42L, friday);

        events.onReservation(moved, VisitEventKind.MOVED, JourneyEvents.GUEST);

        assertThat(repository.findByReservation(42L).orElseThrow().visitDay()).isEqualTo(friday);
    }

    @Test
    void nullOrderIsIgnored() {
        events.onReservation(null, VisitEventKind.REQUESTED, JourneyEvents.BOT);

        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM guest_visits", Integer.class)).isZero();
    }
}
