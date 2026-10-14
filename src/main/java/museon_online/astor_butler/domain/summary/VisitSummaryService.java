package museon_online.astor_butler.domain.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.PhoneMask;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurn;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelProfile;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ToolCallingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Summarizes finished visits from the ledger with one model call, stores the summary and proposes the model's lessons
 * for the agents. Runs every 10 minutes over visits at AFTER or RETURNED without a summary; a manager can rebuild one
 * from the board. Nothing happens while {@code ASTOR_VISIT_SUMMARY_ENABLED} is off; a broken answer stores nothing.
 */
@Slf4j
@Service
public class VisitSummaryService {

    static final String PURPOSE = "visit-summary";
    static final String SCENARIO = "VisitSummary";
    static final int BATCH = 20;
    /** A visit whose summary failed this often is left until the service restarts, so it cannot clog the batch. */
    static final int MAX_ATTEMPTS = 3;
    static final int MAX_LINE = 400;
    static final int HEAD_LINES = 30;
    static final int TAIL_LINES = 120;
    static final String HIDDEN = "[скрыто]";
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{10,}");
    private static final Pattern FENCE = Pattern.compile("^```[a-zA-Z]*\\s*|\\s*```$");
    private static final Pattern LESSON = Pattern.compile("^(CONCIERGE|BUTLER)\\s*:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final List<String> KEYS = List.of("guestWanted", "venueOffered", "outcome", "stuck", "nextTimeGuest",
            "nextTimeAgents");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String PREAMBLE = """
            Ты аналитик ресторана. По ленте визита ответь строго JSON без пояснений и без markdown:
            {"guestWanted": "что хотел гость", "venueOffered": "что предложил ресторан", "outcome": "чем закончилось",
             "stuck": ["где разговор застрял"], "nextTimeGuest": ["что помнить о госте в следующий раз"],
             "nextTimeAgents": ["CONCIERGE: урок Консьержу", "BUTLER: урок Батлеру"]}
            Каждая строка — коротко, до 20 слов; в каждом списке не больше 3 пунктов, пустой список, если сказать нечего.
            Каждый пункт nextTimeAgents начинается с CONCIERGE: или BUTLER: — кому урок.
            Не пиши телефоны, имена и скрытый текст.
            Лента (время ресторана, СТОРОНА/канал: текст):
            """;

    private final VisitTurnRepository turns;
    private final GuestVisitRepository visits;
    private final VisitSummaryRepository summaries;
    private final AgentLessonRepository lessons;
    private final ModelGateway model;
    private final SummaryProperties properties;
    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();
    private ToolCallingModel chatModel;

    @Autowired
    public VisitSummaryService(VisitTurnRepository turns, GuestVisitRepository visits, VisitSummaryRepository summaries,
                               AgentLessonRepository lessons, ModelGateway model, SummaryProperties properties,
                               ObjectMapper mapper, GuestJourneyProperties journey) {
        this(turns, visits, summaries, lessons, model, properties, mapper, ZoneId.of(journey.getZoneId()));
    }

    VisitSummaryService(VisitTurnRepository turns, GuestVisitRepository visits, VisitSummaryRepository summaries,
                        AgentLessonRepository lessons, ModelGateway model, SummaryProperties properties, ObjectMapper mapper,
                        ZoneId zone) {
        this.turns = turns;
        this.visits = visits;
        this.summaries = summaries;
        this.lessons = lessons;
        this.model = model;
        this.properties = properties;
        this.mapper = mapper;
        this.zone = zone;
    }

    /** Only the chat API takes a model name per call: with {@code astor.summary.model} set, summaries go through it. */
    @Autowired(required = false)
    public void setChatModel(ToolCallingModel chatModel) {
        this.chatModel = chatModel;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    @Scheduled(fixedDelayString = "${astor.summary.poll-ms:600000}", initialDelayString = "${astor.summary.initial-delay-ms:120000}")
    public void summarizeFinishedVisits() {
        if (!properties.isEnabled()) {
            return;
        }
        Set<Long> skipped = failures.entrySet().stream().filter(entry -> entry.getValue() >= MAX_ATTEMPTS)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        for (long visitId : summaries.awaitingSummary(skipped, BATCH)) {
            try {
                if (summarize(visitId).isPresent()) {
                    failures.remove(visitId);
                } else {
                    failures.merge(visitId, 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                failures.merge(visitId, 1, Integer::sum);
                log.warn("Visit summary failed: visitId={} error={}", visitId, e.getClass().getSimpleName());
            }
        }
    }

    /** Builds (or rebuilds) the visit's summary; empty when off, without turns, or when the model answer is unusable. */
    public Optional<VisitSummary> summarize(long visitId) {
        if (!properties.isEnabled() || visits.findById(visitId).isEmpty()) {
            return Optional.empty();
        }
        List<VisitTurn> ledger = turns.ofVisit(visitId);
        if (ledger.isEmpty()) {
            return Optional.empty();
        }
        String answer;
        try {
            answer = ask(visitId, prompt(ledger));
        } catch (RuntimeException e) {
            log.warn("Visit summary model call failed: visitId={} error={}", visitId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        Optional<VisitSummary> parsed = parse(visitId, answer);
        if (parsed.isEmpty()) {
            log.warn("Visit summary answer is not the expected JSON: visitId={}", visitId);
            return Optional.empty();
        }
        VisitSummary fresh = parsed.get();
        // A rebuild keeps the notes already there (a manager's included) after the new ones.
        List<String> nextTimeGuest = merge(fresh.nextTimeGuest(),
                summaries.find(visitId).map(VisitSummary::nextTimeGuest).orElse(List.of()));
        VisitSummary summary = new VisitSummary(visitId, fresh.guestWanted(), fresh.venueOffered(), fresh.outcome(),
                fresh.stuck(), nextTimeGuest, fresh.nextTimeAgents(), null);
        summaries.save(summary);
        for (String item : summary.nextTimeAgents()) {
            var lesson = LESSON.matcher(item.trim());
            if (lesson.matches()) {
                lessons.propose(visitId, lesson.group(1).toUpperCase(Locale.ROOT), lesson.group(2).trim());
            }
        }
        return summaries.find(visitId);
    }

    /**
     * A manager's note for next time: an accepted Butler lesson, and a line in the visit summary's {@code nextTimeGuest}
     * (an empty summary is created when the visit has none). False when there is no such visit.
     */
    public boolean addNextTime(long visitId, String text) {
        if (text == null || text.isBlank() || visits.findById(visitId).isEmpty()) {
            return false;
        }
        String note = PhoneMask.mask(text.trim());
        lessons.addAccepted(visitId, "BUTLER", note);
        VisitSummary current = summaries.find(visitId).orElse(VisitSummary.empty(visitId));
        summaries.save(new VisitSummary(visitId, current.guestWanted(), current.venueOffered(), current.outcome(),
                current.stuck(), merge(current.nextTimeGuest(), List.of(note)), current.nextTimeAgents(), current.createdAt()));
        return true;
    }

    String prompt(List<VisitTurn> ledger) {
        List<String> lines = ledger.stream().map(this::line).toList();
        if (lines.size() > HEAD_LINES + TAIL_LINES) {
            List<String> cut = new ArrayList<>(lines.subList(0, HEAD_LINES));
            cut.add("… (" + (lines.size() - HEAD_LINES - TAIL_LINES) + " реплик пропущено)");
            cut.addAll(lines.subList(lines.size() - TAIL_LINES, lines.size()));
            lines = cut;
        }
        return PREAMBLE + String.join("\n", lines);
    }

    private String line(VisitTurn turn) {
        String text;
        if (turn.text() != null) {
            text = turn.text().replace('\n', ' ');
        } else if (turn.payload() != null && Boolean.TRUE.equals(turn.payload().get("hidden"))) {
            text = HIDDEN;
        } else {
            Object tool = turn.payload() == null ? null : turn.payload().get("tool");
            text = tool == null ? "" : String.valueOf(tool);
        }
        if (turn.kind() != TurnKind.MESSAGE) {
            text = ("(" + turn.kind().name() + ") " + text).trim();
        }
        text = LONG_DIGITS.matcher(PhoneMask.mask(text)).replaceAll("***");
        if (text.length() > MAX_LINE) {
            text = text.substring(0, MAX_LINE) + "…";
        }
        return "[" + TIME.format(turn.at().atZone(zone)) + "] " + side(turn.side()) + "/" + turn.channel().name() + ": " + text;
    }

    private static String side(TurnSide side) {
        return switch (side) {
            case GUEST -> "ГОСТЬ";
            case CONCIERGE -> "КОНСЬЕРЖ";
            case BUTLER -> "БАТЛЕР";
            case STAFF -> "ПЕРСОНАЛ";
            case SYSTEM -> "СИСТЕМА";
        };
    }

    private String ask(long visitId, String prompt) {
        Map<String, Object> metadata = Map.of("visitId", visitId, "scenario", SCENARIO);
        String override = properties.getModel();
        if (override != null && !override.isBlank() && chatModel != null) {
            return chatModel.chat(new ModelChatRequest(List.of(ModelChatMessage.user(prompt)), List.of(), ModelProfile.FRONTLINE,
                    PURPOSE, metadata, override.trim())).text();
        }
        return model.generateText(new ModelTextRequest(prompt, SCENARIO, null, PURPOSE, ModelProfile.FRONTLINE, metadata)).text();
    }

    Optional<VisitSummary> parse(long visitId, String answer) {
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        String json = FENCE.matcher(answer.trim()).replaceAll("").trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < start) {
            return Optional.empty();
        }
        json = json.substring(start, end + 1);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || KEYS.stream().anyMatch(key -> !root.has(key))) {
            return Optional.empty();
        }
        for (String key : List.of("stuck", "nextTimeGuest", "nextTimeAgents")) {
            if (!root.get(key).isArray()) {
                return Optional.empty();
            }
        }
        return Optional.of(new VisitSummary(visitId, text(root.get("guestWanted")), text(root.get("venueOffered")),
                text(root.get("outcome")), list(root.get("stuck")), list(root.get("nextTimeGuest")),
                list(root.get("nextTimeAgents")), null));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isValueNode() ? node.asText() : node.toString();
        return value.isBlank() ? null : PhoneMask.mask(value.trim());
    }

    private static List<String> list(JsonNode array) {
        List<String> items = new ArrayList<>();
        array.forEach(node -> {
            String value = text(node);
            if (value != null) {
                items.add(value);
            }
        });
        return items;
    }

    private static List<String> merge(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }
}
