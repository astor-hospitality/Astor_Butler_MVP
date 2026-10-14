package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnLoopTest {

    private static final long CHAT = 421441838L;
    /** Wednesday 14.10.2026, 13:00 in Yekaterinburg. */
    private static final Instant VISIT = Instant.parse("2026-10-14T08:00:00Z");

    private JourneyTestDatabase database;
    private GuestVisitRepository visits;
    private GuestJourneyProperties properties;
    private UsefulnessPolicyProperties policyProperties;
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        visits = new GuestVisitRepository(database.jdbc());
        properties = new GuestJourneyProperties();
        properties.setEnabled(true);
        properties.getReturnLoop().setEnabled(true);
        policyProperties = new UsefulnessPolicyProperties();
        policyProperties.setEnabled(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private ReturnLoop loop(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        JourneyRecorder recorder = new JourneyRecorder(visits, properties, clock);
        return new ReturnLoop(properties, visits, recorder, new UsefulnessPolicy(visits, policyProperties),
                (chatId, text) -> sent.add(chatId + ": " + text), reservationId -> Optional.of(new ReturnLoop.Guest("Мария", "Кирилл")),
                chatId -> somethingToRepeat, clock);
    }

    private boolean somethingToRepeat = true;

    private void reviewed(long reservation, String rating) {
        new JourneyRecorder(visits, properties, Clock.fixed(VISIT, ZoneOffset.UTC))
                .record(reservation, CHAT, null, "AERIS", VISIT, VisitEventKind.REVIEW, "GUEST", rating);
    }

    private void visitAt(long reservation, JourneyStage stage, Instant day) {
        JourneyRecorder recorder = new JourneyRecorder(visits, properties, Clock.fixed(day, ZoneOffset.UTC));
        recorder.record(reservation, CHAT, "+79120000000", "AERIS", day, VisitEventKind.REQUESTED, "BOT", null);
        if (stage.order() >= JourneyStage.SEATED.order() && !stage.isExit()) {
            recorder.record(reservation, CHAT, "+79120000000", "AERIS", day, VisitEventKind.SEATED, "STAFF", null);
        }
        if (stage == JourneyStage.BILL || stage == JourneyStage.AFTER) {
            recorder.record(reservation, CHAT, "+79120000000", "AERIS", day, VisitEventKind.BILL_REQUESTED, "GUEST", null);
        }
    }

    @Test
    void afterMessageNextDayOnlyOnce() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        reviewed(42L, "stars=5");

        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();
        loop(Instant.parse("2026-10-15T08:00:00Z")).tick();

        assertThat(sent).containsExactly(CHAT + ": " + ReturnLoop.thanks("Кирилл") + ReturnLoop.STOP_HINT);
        assertThat(visits.findByReservation(42L).orElseThrow().stage()).isEqualTo(JourneyStage.AFTER);
    }

    @Test
    void nothingIsSentAtNight() {
        visitAt(42L, JourneyStage.BILL, VISIT);

        loop(Instant.parse("2026-10-14T20:30:00Z")).tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void inviteAfterSevenDaysOnlyOnce() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();
        sent.clear();

        loop(Instant.parse("2026-10-20T07:00:00Z")).tick();
        assertThat(sent).isEmpty();
        loop(Instant.parse("2026-10-21T07:00:00Z")).tick();
        loop(Instant.parse("2026-10-21T09:00:00Z")).tick();

        assertThat(sent).containsExactly(CHAT + ": Мария, давно не виделись! Собрать «как в прошлый раз» на четверг? "
                + "Просто ответьте «как в прошлый раз».");
    }

    @Test
    void noMessagesWhenOptedOut() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        visits.optOut(CHAT);

        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void noInviteIfGuestAlreadyBookedAgain() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();
        sent.clear();
        visitAt(43L, JourneyStage.REQUESTED, Instant.parse("2026-10-17T08:00:00Z"));

        loop(Instant.parse("2026-10-21T07:00:00Z")).tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void openVisitPastItsDayWithoutSeatedBecomesNoShow() {
        visitAt(42L, JourneyStage.REQUESTED, VISIT);

        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();

        assertThat(visits.findByReservation(42L).orElseThrow().stage()).isEqualTo(JourneyStage.NO_SHOW);
        assertThat(sent).isEmpty();
    }

    @Test
    void offWhenTheReturnLoopIsOff() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        properties.getReturnLoop().setEnabled(false);

        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void noNextDayMessageWithoutAReview() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        new JourneyRecorder(visits, properties, Clock.fixed(VISIT, ZoneOffset.UTC))
                .record(42L, CHAT, null, "AERIS", VISIT, VisitEventKind.TIP, "GUEST", null);

        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();

        assertThat(sent).isEmpty();
        assertThat(visits.findByReservation(42L).orElseThrow().stage()).isEqualTo(JourneyStage.AFTER);
    }

    @Test
    void aLowRatingIsNotThankedAsAPleasure() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        reviewed(42L, "stars=2");

        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();

        assertThat(sent).containsExactly(CHAT + ": " + ReturnLoop.SORRY + ReturnLoop.STOP_HINT);
    }

    @Test
    void noInviteWhenThereIsNothingToRepeat() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();
        sent.clear();
        somethingToRepeat = false;

        loop(Instant.parse("2026-10-21T07:00:00Z")).tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void oldInvitedVisitsDoNotCrowdOutANewOne() {
        for (long r = 1000; r < 1120; r++) {
            Instant day = Instant.parse("2026-10-01T08:00:00Z");
            JourneyRecorder recorder = new JourneyRecorder(visits, properties, Clock.fixed(day, ZoneOffset.UTC));
            recorder.record(r, 7L, null, "AERIS", day, VisitEventKind.SEATED, "STAFF", null);
            recorder.record(r, 7L, null, "AERIS", day, VisitEventKind.AFTER_SENT, "BOT", null);
            recorder.record(r, 7L, null, "AERIS", day, VisitEventKind.INVITED, "BOT", null);
        }
        visitAt(42L, JourneyStage.BILL, VISIT);
        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();
        sent.clear();

        loop(Instant.parse("2026-10-21T07:00:00Z")).tick();

        assertThat(sent).hasSize(1);
    }

    @Test
    void visitsOlderThanTheWindowAreNotInvitedWhenTheLoopIsTurnedOnLate() {
        visitAt(42L, JourneyStage.BILL, VISIT);
        loop(Instant.parse("2026-10-15T07:00:00Z")).tick();
        sent.clear();

        loop(Instant.parse("2026-12-01T07:00:00Z")).tick();

        assertThat(sent).isEmpty();
    }
}
