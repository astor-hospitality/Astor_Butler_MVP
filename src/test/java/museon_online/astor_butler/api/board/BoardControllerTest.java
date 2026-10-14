package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLesson;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.SummaryProperties;
import museon_online.astor_butler.domain.summary.VisitSummary;
import museon_online.astor_butler.domain.summary.VisitSummaryRepository;
import museon_online.astor_butler.domain.summary.VisitSummaryService;
import museon_online.astor_butler.model.ModelGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

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

    @Test
    @SuppressWarnings("unchecked")
    void phonesInPayloadsAreMasked() {
        long reservation = database.reservation("Мария Кузнецова", null, null, NOW);
        long visit = database.visit(reservation, null, "REQUESTED", NOW, 0);
        database.turn(visit, "CONCIERGE", "TOOL_CALL", null, "{\"phone\":\"+79120000060\",\"guest\":{\"phones\":"
                + "[\"8 912 000-00-61\"],\"party\":2}}", NOW);

        java.util.Map<String, Object> payload = controller.turns(visit).getFirst().payload();

        assertThat(payload).containsEntry("phone", "+7 912 ***-**-60");
        assertThat((java.util.Map<String, Object>) payload.get("guest")).containsEntry("phones", List.of("+7 912 ***-**-61"))
                .containsEntry("party", 2);
        assertThat(payload.toString()).doesNotContainPattern("\\d{3}-\\d{2}-\\d{2}").doesNotContain("0000060");
    }

    @Test
    void summariesAndLessonsOnTheBoard() {
        ObjectMapper mapper = new ObjectMapper();
        VisitSummaryRepository summaries = new VisitSummaryRepository(database.jdbc(), mapper);
        AgentLessonRepository lessons = new AgentLessonRepository(database.jdbc());
        SummaryProperties properties = new SummaryProperties();
        VisitSummaryService service = new VisitSummaryService(new VisitTurnRepository(database.jdbc(), mapper),
                new GuestVisitRepository(database.jdbc()), summaries, lessons, mock(ModelGateway.class), properties, mapper,
                new museon_online.astor_butler.domain.journey.GuestJourneyProperties());
        controller.setSummaries(service, summaries, lessons);
        long summarized = database.visit(database.reservation("Мария Кузнецова", null, null, NOW), null, "AFTER", NOW, 3);
        long plain = database.visit(database.reservation("Пётр", null, null, NOW), null, "AFTER", NOW.plusSeconds(60), 1);
        summaries.save(new VisitSummary(summarized, "стол у окна", "стол 7", "пришла", List.of(), List.of("любит окно"),
                List.of("BUTLER: не переспрашивать"), null));
        lessons.propose(summarized, "BUTLER", "не переспрашивать");
        lessons.propose(summarized, "CONCIERGE", "сразу называть столы");

        List<BoardController.BoardVisit> visits = controller.visits(NOW.minus(1, ChronoUnit.DAYS).toString(),
                NOW.plus(1, ChronoUnit.DAYS).toString());
        assertThat(visits).filteredOn(visit -> visit.id() == summarized).singleElement()
                .satisfies(visit -> assertThat(visit.hasSummary()).isTrue());
        assertThat(visits).filteredOn(visit -> visit.id() == plain).singleElement()
                .satisfies(visit -> assertThat(visit.hasSummary()).isFalse());

        assertThat(controller.summary(summarized).getBody().nextTimeGuest()).containsExactly("любит окно");
        assertThat(controller.summary(plain).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Rebuilding while summaries are off is refused.
        assertThat(controller.rebuildSummary(summarized).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        List<AgentLesson> proposed = controller.lessons("PROPOSED");
        assertThat(proposed).hasSize(2);
        long butler = proposed.stream().filter(lesson -> lesson.side().equals("BUTLER")).findFirst().orElseThrow().id();
        long concierge = proposed.stream().filter(lesson -> lesson.side().equals("CONCIERGE")).findFirst().orElseThrow().id();
        assertThat(controller.acceptLesson(butler, Map.of("text", "не переспрашивать время")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.rejectLesson(concierge).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.rejectLesson(9_999L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(lessons.accepted("BUTLER")).containsExactly("не переспрашивать время");
        assertThat(controller.lessons("PROPOSED")).isEmpty();

        ResponseEntity<?> note = controller.nextTime(plain, Map.of("text", "просил без лука, тел. 89120000060"));
        assertThat(note.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summaries.find(plain).orElseThrow().nextTimeGuest()).containsExactly("просил без лука, тел. +7 912 ***-**-60");
        assertThat(lessons.accepted("BUTLER")).contains("просил без лука, тел. +7 912 ***-**-60");
        assertThat(controller.nextTime(9_999L, Map.of("text", "x")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.nextTime(plain, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.lessons("WHATEVER")).isEmpty();
    }
}
