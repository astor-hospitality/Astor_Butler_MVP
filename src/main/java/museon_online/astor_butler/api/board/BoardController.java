package museon_online.astor_butler.api.board;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import museon_online.astor_butler.domain.ledger.PhoneMask;
import museon_online.astor_butler.domain.ledger.VisitTurn;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLesson;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.VisitSummary;
import museon_online.astor_butler.domain.summary.VisitSummaryRepository;
import museon_online.astor_butler.domain.summary.VisitSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The closed board: visits, each visit's turns, the figures and the health map, plus a live stream; visit summaries and
 * the agents' lessons a manager accepts or rejects. Closed by {@link BoardTokenFilter}; names and phones leave masked,
 * hidden turns leave without text.
 */
@RestController
@RequestMapping("/api/board/v1")
@Tag(name = "Board", description = "Closed board: visits, turns, metrics, health map")
public class BoardController {

    static final String HIDDEN_TEXT = "текст скрыт";
    static final String UNKNOWN_GUEST = "Гость";
    static final Duration MAX_WINDOW = Duration.ofDays(93);
    static final Duration DEFAULT_PAST = Duration.ofDays(7);
    static final Duration DEFAULT_AHEAD = Duration.ofDays(1);

    private final BoardQueries queries;
    private final VisitTurnRepository turns;
    private final BoardMetrics metrics;
    private final HealthMap healthMap;
    private final BoardStream stream;
    private VisitSummaryService summaryService;
    private VisitSummaryRepository summaries;
    private AgentLessonRepository lessons;

    public BoardController(BoardQueries queries, VisitTurnRepository turns, BoardMetrics metrics, HealthMap healthMap,
                           BoardStream stream) {
        this.queries = queries;
        this.turns = turns;
        this.metrics = metrics;
        this.healthMap = healthMap;
        this.stream = stream;
    }

    @Autowired(required = false)
    public void setSummaries(VisitSummaryService summaryService, VisitSummaryRepository summaries, AgentLessonRepository lessons) {
        this.summaryService = summaryService;
        this.summaries = summaries;
        this.lessons = lessons;
    }

    public record BoardVisit(long id, long reservationId, String stage, String visitDay, String guest, String phone,
                             String table, int guestMessages, String lastTurnAt, boolean hasSummary) {
    }

    public record BoardTurn(String at, String side, String channel, String kind, String text, Map<String, Object> payload,
                            String goal) {
    }

    @GetMapping("/visits")
    @Operation(summary = "Visits of the window (by visit day), masked")
    public List<BoardVisit> visits(@RequestParam(name = "from", required = false) String from,
                                   @RequestParam(name = "to", required = false) String to) {
        Instant[] window = window(from, to);
        List<BoardQueries.VisitRow> rows = queries.visits(window[0], window[1]);
        Set<Long> summarized = summaries == null || rows.isEmpty() ? Set.of()
                : summaries.withSummary(rows.stream().map(BoardQueries.VisitRow::id).toList());
        return rows.stream()
                .map(row -> new BoardVisit(row.id(), row.reservationId(), row.stage(), text(row.visitDay()),
                        maskName(row.guestName()), maskPhone(row.guestPhone()), row.table(), row.guestMessages(),
                        text(row.lastTurnAt()), summarized.contains(row.id())))
                .toList();
    }

    @GetMapping("/visits/{id}/summary")
    @Operation(summary = "The visit's summary; 404 when it has none")
    public ResponseEntity<VisitSummary> summary(@PathVariable("id") long id) {
        if (summaries == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.of(summaries.find(id).map(BoardController::masked));
    }

    @PostMapping("/visits/{id}/summary")
    @Operation(summary = "Rebuild the visit's summary; 503 while summaries are off, 422 when it could not be built")
    public ResponseEntity<VisitSummary> rebuildSummary(@PathVariable("id") long id) {
        if (summaryService == null || !summaryService.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Optional<VisitSummary> rebuilt = summaryService.summarize(id);
        return rebuilt.map(summary -> ResponseEntity.ok(masked(summary)))
                .orElseGet(() -> ResponseEntity.unprocessableEntity().build());
    }

    @PostMapping("/visits/{id}/next-time")
    @Operation(summary = "A manager's note for next time (body {text}): an accepted Butler lesson and a summary line")
    public ResponseEntity<VisitSummary> nextTime(@PathVariable("id") long id,
                                                 @RequestBody(required = false) Map<String, String> body) {
        String text = body == null ? null : body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (summaryService == null || summaries == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!summaryService.addNextTime(id, text)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(summaries.find(id).map(BoardController::masked));
    }

    @GetMapping("/lessons")
    @Operation(summary = "The agents' lessons with this status (PROPOSED by default), newest first")
    public List<AgentLesson> lessons(@RequestParam(name = "status", required = false, defaultValue = "PROPOSED") String status) {
        if (lessons == null) {
            return List.of();
        }
        AgentLesson.Status wanted;
        try {
            wanted = AgentLesson.Status.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return lessons.withStatus(wanted).stream().map(BoardController::masked).toList();
    }

    @PostMapping("/lessons/{id}/accept")
    @Operation(summary = "Accept a lesson; a non-blank body {text} replaces its text")
    public ResponseEntity<AgentLesson> acceptLesson(@PathVariable("id") long id,
                                                    @RequestBody(required = false) Map<String, String> body) {
        if (lessons == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String text = body == null ? null : body.get("text");
        if (!lessons.accept(id, text == null ? null : PhoneMask.mask(text))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(lessons.find(id).map(BoardController::masked));
    }

    @PostMapping("/lessons/{id}/reject")
    @Operation(summary = "Reject a lesson: it never reaches the agent")
    public ResponseEntity<AgentLesson> rejectLesson(@PathVariable("id") long id) {
        if (lessons == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!lessons.reject(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(lessons.find(id).map(BoardController::masked));
    }

    @GetMapping("/visits/{id}/turns")
    @Operation(summary = "The visit's turns in time order")
    public List<BoardTurn> turns(@PathVariable("id") long id) {
        return turns.ofVisit(id).stream().map(BoardController::turn).toList();
    }

    @GetMapping("/metrics")
    @Operation(summary = "Figures of the window")
    public BoardMetrics.Metrics metrics(@RequestParam(name = "from", required = false) String from,
                                        @RequestParam(name = "to", required = false) String to) {
        Instant[] window = window(from, to);
        return metrics.metrics(window[0], window[1]);
    }

    @GetMapping("/health-map")
    @Operation(summary = "One light per part of the architecture")
    public List<HealthMap.Component> healthMap() {
        return healthMap.map();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live stream: event turn with {visitId}")
    public SseEmitter stream() {
        return stream.open();
    }

    private static BoardTurn turn(VisitTurn turn) {
        String text = turn.text() == null ? HIDDEN_TEXT : PhoneMask.mask(turn.text());
        return new BoardTurn(text(turn.at()), turn.side().name(), turn.channel().name(), turn.kind().name(), text,
                masked(turn.payload()), turn.goal());
    }

    /** The ledger masks only the text; payloads (tool arguments, results) are masked here, every string at any depth. */
    @SuppressWarnings("unchecked")
    static <T> T masked(T value) {
        if (value instanceof String text) {
            return (T) PhoneMask.mask(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, masked(item)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(masked(item)));
            return (T) copy;
        }
        return value;
    }

    private static VisitSummary masked(VisitSummary summary) {
        return new VisitSummary(summary.visitId(), PhoneMask.mask(summary.guestWanted()), PhoneMask.mask(summary.venueOffered()),
                PhoneMask.mask(summary.outcome()), masked(summary.stuck()), masked(summary.nextTimeGuest()),
                masked(summary.nextTimeAgents()), summary.createdAt());
    }

    private static AgentLesson masked(AgentLesson lesson) {
        return new AgentLesson(lesson.id(), lesson.visitId(), lesson.side(), PhoneMask.mask(lesson.text()), lesson.status(),
                lesson.createdAt(), lesson.decidedAt());
    }

    /** «Мария К.»: the first name and the surname's initial; one word as is; nothing (or digits) → «Гость». */
    static String maskName(String name) {
        if (name == null || name.isBlank() || name.chars().filter(Character::isDigit).count() >= 5) {
            return UNKNOWN_GUEST;
        }
        String[] words = name.trim().split("\\s+");
        String first = clip(words[0]);
        if (words.length == 1) {
            return first;
        }
        return first + " " + words[1].substring(0, words[1].offsetByCodePoints(0, 1)).toUpperCase() + ".";
    }

    /** {@code +7 912 ***-**-60}: country code, first three and last two digits; other numbers keep only the last two. */
    static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 11 && (digits.charAt(0) == '7' || digits.charAt(0) == '8')) {
            return "+7 " + digits.substring(1, 4) + " ***-**-" + digits.substring(9);
        }
        if (digits.length() == 10 && digits.charAt(0) == '9') {
            return "+7 " + digits.substring(0, 3) + " ***-**-" + digits.substring(8);
        }
        return digits.length() < 4 ? null : "***-**-" + digits.substring(digits.length() - 2);
    }

    private static String clip(String word) {
        return word.length() <= 40 ? word : word.substring(0, 40);
    }

    private static Instant[] window(String from, String to) {
        Instant now = Instant.now();
        Instant start = from == null || from.isBlank() ? now.minus(DEFAULT_PAST) : parse(from);
        Instant end = to == null || to.isBlank() ? now.plus(DEFAULT_AHEAD) : parse(to);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to, at most 93 days apart");
        }
        return new Instant[]{start, end};
    }

    private static Instant parse(String value) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are ISO-8601 instants");
        }
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
