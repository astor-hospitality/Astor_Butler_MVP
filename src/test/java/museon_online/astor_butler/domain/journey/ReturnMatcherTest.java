package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnMatcherTest {

    private static final Instant FIRST = Instant.parse("2026-10-14T08:00:00Z");

    private JourneyTestDatabase database;
    private GuestVisitRepository visits;
    private GuestJourneyProperties properties;

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        visits = new GuestVisitRepository(database.jdbc());
        properties = new GuestJourneyProperties();
        properties.setEnabled(true);
        properties.getReturnLoop().setEnabled(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private void book(long reservation, String phone, Instant at) {
        Clock clock = Clock.fixed(at, ZoneOffset.UTC);
        new JourneyRecorder(visits, properties, clock).record(reservation, 421L, phone, "AERIS", at, VisitEventKind.REQUESTED, "BOT", null);
        new ReturnMatcher(properties, visits, new JourneyRecorder(visits, properties, clock), clock).onNewReservation(reservation, phone);
    }

    private void seat(long reservation) {
        new JourneyRecorder(visits, properties, Clock.fixed(FIRST, ZoneOffset.UTC))
                .record(reservation, 421L, null, "AERIS", FIRST, VisitEventKind.SEATED, "STAFF", null);
    }

    @Test
    void samePhoneWithin30DaysMarksReturned() {
        book(42L, "+79120000000", FIRST);
        seat(42L);
        book(50L, "+79120000000", FIRST.plusSeconds(9 * 86_400));

        GuestVisit first = visits.findByReservation(42L).orElseThrow();
        assertThat(first.stage()).isEqualTo(JourneyStage.RETURNED);
        assertThat(first.returnedVisitId()).isEqualTo(visits.findByReservation(50L).orElseThrow().id());
    }

    @Test
    void after30DaysNotReturned() {
        book(42L, "+79120000000", FIRST);
        seat(42L);
        book(50L, "+79120000000", FIRST.plusSeconds(31 * 86_400));

        assertThat(visits.findByReservation(42L).orElseThrow().returnedVisitId()).isNull();
    }

    @Test
    void aBookingThatNeverHappenedIsNotAVisit() {
        book(42L, "+79120000000", FIRST);
        book(50L, "+79120000000", FIRST.plusSeconds(86_400));

        assertThat(visits.findByReservation(42L).orElseThrow().returnedVisitId()).isNull();
    }

    @Test
    void noPhoneNoMatch() {
        book(42L, null, FIRST);
        book(50L, null, FIRST.plusSeconds(86_400));

        assertThat(visits.findByReservation(42L).orElseThrow().returnedVisitId()).isNull();
    }

    @Test
    void aCancelledVisitDoesNotCountAsAVisit() {
        book(42L, "+79120000000", FIRST);
        new JourneyRecorder(visits, properties, Clock.fixed(FIRST, ZoneOffset.UTC))
                .record(42L, 421L, null, "AERIS", FIRST, VisitEventKind.CANCELLED, "GUEST", null);
        book(50L, "+79120000000", FIRST.plusSeconds(86_400));

        assertThat(visits.findByReservation(42L).orElseThrow().returnedVisitId()).isNull();
    }
}
