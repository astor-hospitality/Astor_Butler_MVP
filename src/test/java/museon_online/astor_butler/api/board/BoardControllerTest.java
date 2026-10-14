package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BoardControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");

    private BoardTestDatabase database;
    private BoardController controller;

    @BeforeEach
    void setUp() {
        database = BoardTestDatabase.create();
        BoardQueries queries = new BoardQueries(database.jdbc(), new ObjectMapper());
        controller = new BoardController(queries, new VisitTurnRepository(database.jdbc(), new ObjectMapper()),
                new BoardMetrics(queries), mock(HealthMap.class), mock(BoardStream.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void visitsAreListedWithMaskedPhonesAndNames() {
        long maria = database.reservation("Мария Кузнецова", "+79120000060", "Стол 7", NOW.minus(1, ChronoUnit.HOURS));
        long mariaVisit = database.visit(maria, "+79120000060", "CONFIRMED", NOW.plus(3, ChronoUnit.HOURS), 5);
        database.turn(mariaVisit, "GUEST", "MESSAGE", "здравствуйте", null, NOW.minus(10, ChronoUnit.MINUTES));
        long anonymous = database.reservation(null, "89120000061", null, NOW);
        database.visit(anonymous, "89120000061", "REQUESTED", NOW.plus(4, ChronoUnit.HOURS), 0);
        long single = database.reservation("Пётр", null, null, NOW);
        database.visit(single, null, "REQUESTED", NOW.plus(5, ChronoUnit.HOURS), 0);

        List<BoardController.BoardVisit> visits = controller.visits(NOW.minus(1, ChronoUnit.DAYS).toString(),
                NOW.plus(1, ChronoUnit.DAYS).toString());

        assertThat(visits).hasSize(3);
        BoardController.BoardVisit first = visits.getFirst();
        assertThat(first.id()).isEqualTo(mariaVisit);
        assertThat(first.reservationId()).isEqualTo(maria);
        assertThat(first.stage()).isEqualTo("CONFIRMED");
        assertThat(first.guest()).isEqualTo("Мария К.");
        assertThat(first.phone()).isEqualTo("+7 912 ***-**-60");
        assertThat(first.table()).isEqualTo("Стол 7");
        assertThat(first.guestMessages()).isEqualTo(5);
        assertThat(first.lastTurnAt()).isEqualTo(NOW.minus(10, ChronoUnit.MINUTES).toString());
        assertThat(first.hasSummary()).isFalse();
        assertThat(visits.get(1).guest()).isEqualTo("Гость");
        assertThat(visits.get(1).phone()).isEqualTo("+7 912 ***-**-61");
        assertThat(visits.get(2).guest()).isEqualTo("Пётр");
        assertThat(visits.get(2).phone()).isNull();
        assertThat(visits.toString()).doesNotContainPattern("\\d{10}").doesNotContain("Кузнецова");
    }

    @Test
    void hiddenTextIsShownAsHidden() {
        long reservation = database.reservation("Мария Кузнецова", null, null, NOW);
        long visit = database.visit(reservation, null, "REQUESTED", NOW, 0);
        database.turn(visit, "GUEST", "MESSAGE", null, "{\"hidden\":true}", NOW);
        database.turn(visit, "CONCIERGE", "MESSAGE", "уточню у ресторана", null, NOW.plusSeconds(1));

        List<BoardController.BoardTurn> turns = controller.turns(visit);

        assertThat(turns).extracting(BoardController.BoardTurn::text).containsExactly("текст скрыт", "уточню у ресторана");
        assertThat(turns.getFirst().side()).isEqualTo("GUEST");
        assertThat(turns.getFirst().payload()).containsEntry("hidden", true);
    }
}
