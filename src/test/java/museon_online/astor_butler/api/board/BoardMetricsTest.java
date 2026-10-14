package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BoardMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");
    private static final Instant FROM = NOW.minus(1, ChronoUnit.DAYS);
    private static final Instant TO = NOW.plus(1, ChronoUnit.DAYS);

    private BoardTestDatabase database;
    private BoardMetrics metrics;

    @BeforeEach
    void setUp() {
        database = BoardTestDatabase.create();
        metrics = new BoardMetrics(new BoardQueries(database.jdbc(), new ObjectMapper()));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void countsGuestMessagesAndForty() {
        database.visit(database.reservation("А", null, null, NOW), null, "REQUESTED", NOW, 12);
        database.visit(database.reservation("Б", null, null, NOW), null, "REQUESTED", NOW, 41);
        // Outside the window: not counted.
        database.visit(database.reservation("В", null, null, NOW), null, "REQUESTED", NOW.minus(5, ChronoUnit.DAYS), 90);

        BoardMetrics.Metrics result = metrics.metrics(FROM, TO);

        assertThat(result.visits()).isEqualTo(2);
        assertThat(result.guestMessagesAvg()).isEqualTo(26.5);
        assertThat(result.visitsWith40Plus()).isEqualTo(1);
    }

    @Test
    void negotiationClosedWhenAReservationOrOrderToolSucceeded() {
        long closed = database.visit(database.reservation("А", null, null, NOW), null, "REQUESTED", NOW, 3);
        database.turn(closed, "CONCIERGE", "MESSAGE", "предложу стол", null, NOW);
        database.turn(closed, "CONCIERGE", "TOOL_RESULT", null, "{\"tool\":\"reservation_create\",\"ok\":true}", NOW);
        long lunch = database.visit(database.reservation("Б", null, null, NOW), null, "REQUESTED", NOW, 3);
        database.turn(lunch, "BUTLER", "TOOL_RESULT", null, "{\"tool\":\"lunch_order_place\",\"ok\":true}", NOW);
        long failed = database.visit(database.reservation("В", null, null, NOW), null, "REQUESTED", NOW, 3);
        database.turn(failed, "BUTLER", "TOOL_RESULT", null, "{\"tool\":\"reservation_create\",\"ok\":false}", NOW);
        long otherTool = database.visit(database.reservation("Г", null, null, NOW), null, "REQUESTED", NOW, 3);
        database.turn(otherTool, "CONCIERGE", "TOOL_RESULT", null, "{\"tool\":\"menu_lookup\",\"ok\":true}", NOW);
        database.visit(database.reservation("Д", null, null, NOW), null, "REQUESTED", NOW, 3); // guest only, no agents
        database.turn(null, "CONCIERGE", "TOOL_RESULT", null, "{\"tool\":\"reservation_create\",\"ok\":true}", NOW);

        BoardMetrics.Metrics result = metrics.metrics(FROM, TO);

        assertThat(result.negotiationsTotal()).isEqualTo(4);
        assertThat(result.negotiationsClosed()).isEqualTo(2);
    }

    @Test
    void confirmLunchModelAndSabyFigures() {
        long first = database.reservation("А", null, null, NOW.minus(10, ChronoUnit.MINUTES));
        long firstVisit = database.visit(first, null, "SEATED", NOW, 0);
        database.event(firstVisit, "CONFIRMED", NOW.minus(8, ChronoUnit.MINUTES));
        long second = database.reservation("Б", null, null, NOW.minus(10, ChronoUnit.MINUTES));
        long secondVisit = database.visit(second, null, "CONFIRMED", NOW, 0);
        database.event(secondVisit, "CONFIRMED", NOW.minus(4, ChronoUnit.MINUTES));
        database.event(secondVisit, "REVIEW", NOW);
        long third = database.reservation("В", null, null, NOW);
        long thirdVisit = database.visit(third, null, "AFTER", NOW, 0);
        database.event(thirdVisit, "CONFIRMED", NOW.plus(4, ChronoUnit.MINUTES));
        database.jdbc().update("UPDATE guest_visits SET returned_visit_id = ? WHERE id = ?", secondVisit, thirdVisit);
        database.jdbc().update("INSERT INTO guest_bills(chat_id, kind, table_reservation_id) VALUES (1, 'BUSINESS_LUNCH', ?)", first);
        database.turn(firstVisit, "BUTLER", "FALLBACK", null, null, NOW);
        for (int i = 1; i <= 20; i++) {
            database.modelCall(i != 3, i == 4, i * 100L, NOW);
        }
        database.modelCall(false, false, 99_999, NOW.minus(3, ChronoUnit.DAYS));
        database.sabyCall(200, "OK", NOW);
        database.sabyCall(500, "HTTP_ERROR", NOW);
        database.sabyCall(0, "IO_ERROR", NOW);

        BoardMetrics.Metrics result = metrics.metrics(FROM, TO);

        assertThat(result.confirmMedianSeconds()).isEqualTo(240L);
        assertThat(result.lunchConversion()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.reviews()).isEqualTo(1);
        assertThat(result.returned()).isEqualTo(1);
        assertThat(result.fallbacks()).isEqualTo(1);
        assertThat(result.modelCalls()).isEqualTo(20);
        assertThat(result.modelErrors()).isEqualTo(2);
        assertThat(result.modelP95Ms()).isEqualTo(1900L);
        assertThat(result.sabyErrors()).isEqualTo(2);
    }
}
