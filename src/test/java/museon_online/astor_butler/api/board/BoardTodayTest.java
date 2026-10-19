package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.api.board.BoardToday.Channel;
import museon_online.astor_butler.api.board.BoardToday.Item;
import museon_online.astor_butler.api.board.BoardToday.Status;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BoardTodayTest {

    /** 14:00 in Yekaterinburg: the venue day is 2026-10-14T19:00Z – 2026-10-15T19:00Z. */
    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-10-14T19:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-10-15T19:00:00Z");
    private static final Instant LUNCH = Instant.parse("2026-10-15T08:00:00Z");

    private BoardTestDatabase database;
    private BoardToday today;
    private BoardController controller;

    @BeforeEach
    void setUp() {
        database = BoardTestDatabase.create();
        BoardQueries queries = new BoardQueries(database.jdbc(), new ObjectMapper());
        today = new BoardToday(queries, ZoneId.of("Asia/Yekaterinburg"), Clock.fixed(NOW, ZoneOffset.UTC));
        controller = new BoardController(queries, new VisitTurnRepository(database.jdbc(), new ObjectMapper()),
                new BoardMetrics(queries), mock(HealthMap.class), mock(BoardStream.class));
        controller.setToday(today);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private long booking(String name, String status, String source, Integer guests, Long telegramId, Instant start,
                         Instant created, String table, String venue, String stage) {
        long reservation = database.booking(name, status, source, guests, telegramId, telegramId, start, created, table);
        return database.visit(reservation, venue, stage, telegramId, created);
    }

    @Test
    void theDayListsBookingsAndLunchesInTimeOrderWithFirstNamesOnly() {
        long maria = database.booking("Мария Кузнецова", "CONFIRMED", "CONCIERGE", 2, 5001L, null,
                LUNCH.plus(15, ChronoUnit.MINUTES), NOW.minus(2, ChronoUnit.HOURS), "Стол 4");
        database.visit(maria, "AERIS", "CONFIRMED", null, NOW.minus(2, ChronoUnit.HOURS));
        long bill = database.lunchBill(maria, 116_000L, 98_000L);
        database.review(bill, 5);
        booking("Пётр", "AWAITING_MANAGER_CONFIRMATION", "TELEGRAM", 4, 5002L, LUNCH,
                NOW.minus(3, ChronoUnit.MINUTES), null, "AERIS", "REQUESTED");
        // Yesterday and tomorrow stay out of the day.
        booking("Вчера", "CONFIRMED", "TELEGRAM", 2, 5003L, DAY_START.minus(1, ChronoUnit.HOURS), NOW.minus(1, ChronoUnit.DAYS),
                null, "AERIS", "AFTER");
        booking("Завтра", "CONFIRMED", "TELEGRAM", 2, 5004L, DAY_END, NOW, null, "AERIS", "CONFIRMED");

        BoardToday.Day day = today.day(DAY_START, DAY_END, null);

        assertThat(day.items()).extracting(Item::guest).containsExactly("Пётр", "Мария");
        Item petr = day.items().get(0);
        assertThat(petr.status()).isEqualTo(Status.WAITING);
        assertThat(petr.channel()).isEqualTo(Channel.BUTLER);
        assertThat(petr.guests()).isEqualTo(4);
        assertThat(petr.lunchRub()).isNull();
        assertThat(petr.waitingSince()).isEqualTo(NOW.minus(3, ChronoUnit.MINUTES).toString());
        assertThat(petr.at()).isEqualTo(LUNCH.toString());
        Item mariaItem = day.items().get(1);
        assertThat(mariaItem.status()).isEqualTo(Status.CONFIRMED);
        assertThat(mariaItem.channel()).isEqualTo(Channel.CONCIERGE);
        assertThat(mariaItem.table()).isEqualTo("Стол 4");
        assertThat(mariaItem.lunchRub()).as("the venue's amount wins over the estimate").isEqualTo(980L);
        assertThat(mariaItem.stars()).isEqualTo(5);
        assertThat(mariaItem.waitingSince()).isNull();
        assertThat(day.totals()).isEqualTo(new BoardToday.Totals(2, 1, 1, 0, 980, 5.0, 1));
    }

    @Test
    void testGuestsNeverReachTheListOrTheCounters() {
        booking("Настоящий", "CONFIRMED", "TELEGRAM", 2, 900_000_000L, LUNCH, NOW, null, "AERIS", "CONFIRMED");
        booking("Тоже настоящий", "CONFIRMED", "TELEGRAM", 2, 900_001_000L, LUNCH, NOW, null, "AERIS", "CONFIRMED");
        booking("Тест первый", "CONFIRMED", "TELEGRAM", 2, 900_000_001L, LUNCH, NOW, null, "AERIS", "CONFIRMED");
        booking("Тест последний", "AWAITING_MANAGER_CONFIRMATION", "TELEGRAM", 2, 900_000_999L, LUNCH,
                NOW.minus(1, ChronoUnit.HOURS), null, "AERIS", "REQUESTED");
        long onlyChat = database.booking("Тест чат", "CONFIRMED", "TELEGRAM", 2, null, null, LUNCH, NOW, null);
        database.visit(onlyChat, "AERIS", "CONFIRMED", 900_000_500L, NOW);
        long concierge = database.booking("Тест консьерж", "CONFIRMED", "CONCIERGE", 2, null, null, LUNCH, NOW, null);
        long conciergeVisit = database.visit(concierge, "AERIS", "CONFIRMED", null, NOW);
        database.lunchBill(concierge, 500_000L, null);
        database.sessionTurn(conciergeVisit, "c-uxtest-0042", NOW);
        booking("Тест Astor", "CONFIRMED", "TELEGRAM", 2, 7L, LUNCH, NOW, null, "AERIS", "CONFIRMED");
        booking("Astor TEST", "CONFIRMED", "TELEGRAM", 2, 8L, LUNCH, NOW, null, "AERIS", "CONFIRMED");
        booking("Тестов", "CONFIRMED", "TELEGRAM", 2, 9L, LUNCH, NOW, null, "AERIS", "CONFIRMED");
        long realSession = database.booking("Консьерж", "CONFIRMED", "CONCIERGE", 2, null, null, LUNCH, NOW, null);
        database.sessionTurn(database.visit(realSession, "AERIS", "CONFIRMED", null, NOW), "c-7f3a", NOW);

        BoardToday.Day day = today.day(DAY_START, DAY_END, null);

        assertThat(day.items()).extracting(Item::guest).containsExactlyInAnyOrder("Настоящий", "Тоже", "Тестов", "Консьерж");
        assertThat(day.totals().requests()).isEqualTo(4);
        assertThat(day.totals().waiting()).isZero();
        assertThat(day.totals().lunchRub()).isZero();
    }

    @Test
    void oneVenueOrAllAndThePickerKnowsEveryVenue() {
        booking("Анна", "CONFIRMED", "TELEGRAM", 2, 1L, LUNCH, NOW, null, "AERIS", "CONFIRMED");
        booking("Борис", "CONFIRMED", "TELEGRAM", 2, 2L, LUNCH, NOW, null, "SECOND", "CONFIRMED");

        assertThat(today.day(DAY_START, DAY_END, "AERIS").items()).extracting(Item::guest).containsExactly("Анна");
        assertThat(today.day(DAY_START, DAY_END, "SECOND").items()).extracting(Item::venue).containsExactly("SECOND");
        BoardToday.Day all = today.day(DAY_START, DAY_END, null);
        assertThat(all.items()).hasSize(2);
        assertThat(all.venues()).containsExactly("AERIS", "SECOND");
    }

    @Test
    void countersSeeOverdueRequestsCancelledLunchesAndTheAverageRating() {
        booking("Долго", "AWAITING_MANAGER_CONFIRMATION", "TELEGRAM", 2, 1L, LUNCH, NOW.minus(10, ChronoUnit.MINUTES),
                null, "AERIS", "REQUESTED");
        booking("Недавно", "AWAITING_MANAGER_CONFIRMATION", "CONCIERGE", 2, 2L, LUNCH, NOW.minus(9, ChronoUnit.MINUTES),
                null, "AERIS", "REQUESTED");
        long cancelled = database.booking("Отмена", "CANCELLED", "TELEGRAM", 2, 3L, 3L, LUNCH, NOW, null);
        database.visit(cancelled, "AERIS", "CANCELLED", 3L, NOW);
        database.lunchBill(cancelled, 120_000L, null);
        long billed = database.booking("Счёт", "SEATED", "TELEGRAM", 3, 4L, 4L, LUNCH, NOW.minus(2, ChronoUnit.HOURS), null);
        database.visit(billed, "AERIS", "BILL", 4L, NOW);
        database.review(database.lunchBill(billed, 150_050L, null), 4);
        long seated = database.booking("За столом", "SEATED", "TELEGRAM", 1, 5L, 5L, LUNCH, NOW.minus(2, ChronoUnit.HOURS), null);
        database.visit(seated, "AERIS", "SEATED", 5L, NOW);
        database.review(database.lunchBill(seated, 60_000L, null), 5);

        BoardToday.Day day = today.day(DAY_START, DAY_END, "AERIS");

        assertThat(day.items()).extracting(Item::status)
                .containsExactly(Status.WAITING, Status.WAITING, Status.CANCELLED, Status.BILL, Status.CONFIRMED);
        assertThat(day.totals()).isEqualTo(new BoardToday.Totals(5, 2, 2, 1, 1501 + 600, 4.5, 2));
    }

    @Test
    void statusReadsTheReservationAndTheVisit() {
        assertThat(BoardToday.status("AWAITING_MANAGER_CONFIRMATION", "REQUESTED")).isEqualTo(Status.WAITING);
        assertThat(BoardToday.status(null, null)).isEqualTo(Status.WAITING);
        assertThat(BoardToday.status("CONFIRMED", "REQUESTED")).isEqualTo(Status.CONFIRMED);
        assertThat(BoardToday.status("SEATED", "SEATED")).isEqualTo(Status.CONFIRMED);
        assertThat(BoardToday.status("REJECTED", "REQUESTED")).isEqualTo(Status.CANCELLED);
        assertThat(BoardToday.status("EXPIRED", "REQUESTED")).isEqualTo(Status.CANCELLED);
        assertThat(BoardToday.status("CONFIRMED", "CANCELLED")).isEqualTo(Status.CANCELLED);
        assertThat(BoardToday.status("CONFIRMED", "NO_SHOW")).isEqualTo(Status.NO_SHOW);
        assertThat(BoardToday.status("SEATED", "AFTER")).isEqualTo(Status.BILL);
        assertThat(BoardToday.firstName("Мария Кузнецова")).isEqualTo("Мария");
        assertThat(BoardToday.firstName("+7 912 000 00 60")).isEqualTo("Гость");
        assertThat(BoardToday.firstName(null)).isEqualTo("Гость");
    }

    @Test
    void theControllerDefaultsToTheVenueDayAndChecksTheVenueCode() {
        booking("Анна", "CONFIRMED", "TELEGRAM", 2, 1L, LUNCH, NOW, null, "AERIS", "CONFIRMED");

        BoardToday.Day day = controller.today(null, null, " aeris ").getBody();

        assertThat(day).isNotNull();
        assertThat(day.from()).isEqualTo(DAY_START.toString());
        assertThat(day.to()).isEqualTo(DAY_END.toString());
        assertThat(day.venue()).isEqualTo("AERIS");
        assertThat(day.items()).extracting(Item::guest).containsExactly("Анна");
        assertThatThrownBy(() -> controller.today(null, null, "AERIS' OR 1=1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void withoutTheDayTheEndpointSaysUnavailable() {
        BoardQueries queries = new BoardQueries(database.jdbc(), new ObjectMapper());
        BoardController bare = new BoardController(queries, new VisitTurnRepository(database.jdbc(), new ObjectMapper()),
                new BoardMetrics(queries), mock(HealthMap.class), mock(BoardStream.class));

        assertThat(bare.today(null, null, null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
