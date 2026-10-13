package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyWeeklyReportTest {

    /** Tuesday 20.10.2026, 10:05 in Yekaterinburg. */
    private static final Instant TUESDAY_MORNING = Instant.parse("2026-10-20T05:05:00Z");

    private JourneyTestDatabase database;
    private GuestVisitRepository visits;
    private final List<String> sent = new ArrayList<>();
    private final Set<String> marks = new HashSet<>();

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        visits = new GuestVisitRepository(database.jdbc());
        // 34 visits last week: 9 came back, 18 with at least 40 guest messages, the rest with 10.
        for (int i = 0; i < 34; i++) {
            GuestVisit visit = visits.open(100 + i, 1000L + i, "+7912000" + String.format("%04d", i), "AERIS",
                    Instant.parse("2026-10-15T08:00:00Z"));
            database.jdbc().update("UPDATE guest_visits SET opened_at = ?, guest_messages = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.parse("2026-10-15T08:00:00Z")), i < 18 ? 70 : 10, visit.id());
            if (i < 9) {
                visits.linkReturn(visit.id(), 999L);
            }
        }
        long anyVisit = visits.findByReservation(100).orElseThrow().id();
        visits.insertEvent(anyVisit, VisitEventKind.VENUE_QUESTION, "GUEST", "PARKING");
        visits.insertEvent(anyVisit, VisitEventKind.VENUE_QUESTION, "GUEST", "PARKING");
        visits.insertEvent(anyVisit, VisitEventKind.VENUE_QUESTION, "GUEST", "WINE");
        visits.insertEvent(anyVisit, VisitEventKind.TABLE_REQUEST, "GUEST", "BREAD");
        visits.insertEvent(anyVisit, VisitEventKind.OPTED_OUT, "GUEST", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private JourneyWeeklyReport report(Instant now, boolean enabled) {
        return new JourneyWeeklyReport(enabled, visits, sent::add, marks::add, Clock.fixed(now, ZoneOffset.UTC), "Asia/Yekaterinburg");
    }

    @Test
    void tuesdayReportSumsUpTheWeekOnce() {
        report(TUESDAY_MORNING, true).tick();
        report(TUESDAY_MORNING.plusSeconds(3600), true).tick();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("Неделя: 34 визита · вернулись 9 (26%) · сообщений гостя в среднем 42 · визитов ≥ 40: 18",
                "частые вопросы: парковка, вино", "просьб за столом: 1", "отписок: 1");
    }

    @Test
    void notBeforeTenOnTuesday() {
        report(Instant.parse("2026-10-20T04:00:00Z"), true).tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void notOnOtherDays() {
        report(Instant.parse("2026-10-22T05:05:00Z"), true).tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void offWhenTheFlagIsOff() {
        report(TUESDAY_MORNING, false).tick();

        assertThat(sent).isEmpty();
    }
}
