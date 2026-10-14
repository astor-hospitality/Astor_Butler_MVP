package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JourneyRecorderTest {

    /** Wednesday 14.10.2026 13:00 in Yekaterinburg (UTC+5). */
    private static final Instant VISIT = Instant.parse("2026-10-14T08:00:00Z");
    private static final Instant BOOKED = Instant.parse("2026-10-13T14:40:00Z");

    private JourneyTestDatabase database;
    private GuestVisitRepository repository;
    private GuestJourneyProperties properties;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        repository = new GuestVisitRepository(database.jdbc());
        properties = new GuestJourneyProperties();
        properties.setEnabled(true);
        clock = new MutableClock(BOOKED);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private JourneyRecorder recorder() {
        return new JourneyRecorder(repository, properties, clock);
    }

    private Optional<GuestVisit> record(long reservation, String phone, VisitEventKind kind, String source) {
        return recorder().record(reservation, 421L, phone, "AERIS", VISIT, kind, source, null);
    }

    @Test
    void disabledRecorderTouchesNothing() {
        GuestVisitRepository mocked = mock(GuestVisitRepository.class);
        properties.setEnabled(false);
        JourneyRecorder off = new JourneyRecorder(mocked, properties, clock);

        assertThat(off.record(1L, 421L, "+79120000000", "AERIS", VISIT, VisitEventKind.REQUESTED, "BOT", null)).isEmpty();
        assertThat(off.countGuestMessage(421L)).isEmpty();
        verifyNoInteractions(mocked);
    }

    @Test
    void firstEventOpensVisitAndMovesStage() {
        GuestVisit visit = record(42L, "+79120000000", VisitEventKind.REQUESTED, "BOT").orElseThrow();

        assertThat(visit.reservationId()).isEqualTo(42L);
        assertThat(visit.stage()).isEqualTo(JourneyStage.REQUESTED);
        assertThat(visit.guestPhone()).isEqualTo("+79120000000");
        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM guest_visit_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void oneShotEventIsWrittenOnce() {
        record(42L, "+79120000000", VisitEventKind.REQUESTED, "BOT");
        record(42L, "+79120000000", VisitEventKind.SEATED, "PRESTO");
        GuestVisit visit = record(42L, "+79120000000", VisitEventKind.SEATED, "PRESTO").orElseThrow();

        assertThat(visit.stage()).isEqualTo(JourneyStage.SEATED);
        assertThat(database.jdbc().queryForObject(
                "SELECT count(*) FROM guest_visit_events WHERE kind = 'SEATED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatableEventIsWrittenEachTime() {
        record(42L, "+79120000000", VisitEventKind.DISH_QUESTION, "GUEST");
        record(42L, "+79120000000", VisitEventKind.DISH_QUESTION, "GUEST");

        assertThat(database.jdbc().queryForObject(
                "SELECT count(*) FROM guest_visit_events WHERE kind = 'DISH_QUESTION'", Integer.class)).isEqualTo(2);
    }

    @Test
    void stageNeverGoesBack() {
        record(42L, "+79120000000", VisitEventKind.SEATED, "STAFF");
        GuestVisit visit = record(42L, "+79120000000", VisitEventKind.CONFIRMED, "BOT").orElseThrow();

        assertThat(visit.stage()).isEqualTo(JourneyStage.SEATED);
    }

    @Test
    void exitAlwaysWins() {
        record(42L, "+79120000000", VisitEventKind.SEATED, "STAFF");
        GuestVisit cancelled = record(42L, "+79120000000", VisitEventKind.CANCELLED, "GUEST").orElseThrow();
        GuestVisit after = record(42L, "+79120000000", VisitEventKind.BILL_REQUESTED, "GUEST").orElseThrow();

        assertThat(cancelled.stage()).isEqualTo(JourneyStage.CANCELLED);
        assertThat(after.stage()).isEqualTo(JourneyStage.CANCELLED);
    }

    @Test
    void optingOutIsAContactPreferenceNotTheEndOfTheVisit() {
        record(42L, "+79120000000", VisitEventKind.REQUESTED, "BOT");
        record(42L, "+79120000000", VisitEventKind.OPTED_OUT, "GUEST");
        GuestVisit visit = record(42L, "+79120000000", VisitEventKind.SEATED, "STAFF").orElseThrow();

        assertThat(visit.stage()).isEqualTo(JourneyStage.SEATED);
    }

    @Test
    void visitWithoutPhoneIsKeptByChat() {
        GuestVisit visit = record(43L, null, VisitEventKind.REQUESTED, "BOT").orElseThrow();

        assertThat(visit.chatId()).isEqualTo(421L);
        assertThat(visit.guestPhone()).isNull();
        assertThat(repository.lastClosedByPhone(null, BOOKED.minusSeconds(86_400))).isEmpty();
    }

    @Test
    void recorderSwallowsRepositoryFailure() {
        GuestVisitRepository broken = mock(GuestVisitRepository.class);
        when(broken.findByReservation(anyLong())).thenThrow(new IllegalStateException("db down"));
        when(broken.activeByChat(anyLong(), org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("db down"));
        JourneyRecorder failing = new JourneyRecorder(broken, properties, clock);

        assertThat(failing.record(1L, 421L, null, "AERIS", VISIT, VisitEventKind.REQUESTED, "BOT", null)).isEmpty();
        assertThat(failing.countGuestMessage(421L)).isEmpty();
    }

    @Test
    void guestMessageCountsOnlyInsideVisitWindow() {
        record(42L, "+79120000000", VisitEventKind.REQUESTED, "BOT");

        clock.set(VISIT);
        assertThat(recorder().countGuestMessage(421L)).isEqualTo(OptionalInt.of(1));
        // The day after the visit, late evening in Yekaterinburg — still inside the window.
        clock.set(Instant.parse("2026-10-15T18:00:00Z"));
        assertThat(recorder().countGuestMessage(421L)).isEqualTo(OptionalInt.of(2));
        // Two days after the visit — outside.
        clock.set(Instant.parse("2026-10-16T08:00:00Z"));
        assertThat(recorder().countGuestMessage(421L)).isEmpty();

        assertThat(repository.findByReservation(42L).orElseThrow().guestMessages()).isEqualTo(2);
    }

    @Test
    void aBookingMadeFromTheTableDoesNotTakeOverTodaysVisit() {
        record(42L, "+79120000000", VisitEventKind.SEATED, "STAFF");
        clock.set(VISIT.plusSeconds(1800));
        Instant nextFriday = Instant.parse("2026-10-16T08:00:00Z");
        recorder().record(43L, 421L, "+79120000000", "AERIS", nextFriday, VisitEventKind.REQUESTED, "BOT", null);

        assertThat(repository.activeByChat(421L, VISIT.plusSeconds(1900)).orElseThrow().reservationId()).isEqualTo(42L);
    }

    @Test
    void guestMessageWithoutVisitIsIgnored() {
        assertThat(recorder().countGuestMessage(999L)).isEmpty();
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
