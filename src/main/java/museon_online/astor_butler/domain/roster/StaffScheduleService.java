package museon_online.astor_butler.domain.roster;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.Entry;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.EntryStatus;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.NewEntry;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.Schedule;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.Status;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelVisionRequest;
import museon_online.astor_butler.model.ModelVisionResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The schedule picture from the «График» topic to a confirmed schedule, and /смена. Transport-free: Telegram glue
 * ({@link StaffScheduleTelegram}) sends the {@link Reply} and edits the message a button was pressed on.
 *
 * <p>Order of events: recognise → match → store (CLARIFYING when some name needs a person's choice, else PROPOSED)
 * → «Верно» makes it CONFIRMED, «Исправить» REJECTED. Only a confirmed schedule answers {@link StaffRoster}.
 */
@Service
@Slf4j
public class StaffScheduleService {

    static final String CALLBACK_PREFIX = "roster:";
    private static final Pattern PICK = Pattern.compile("^roster:pick:(\\d+):(\\d|x)$");
    private static final Pattern DECISION = Pattern.compile("^roster:(ok|fix):(\\d+)$");
    private static final Pattern COMMAND = Pattern.compile("^/(смена|shift)(?:@\\w+)?(?:\\s+(.*))?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    public record Button(String label, String data) {
    }

    /** A message for the topic; no buttons when {@code buttons} is empty. */
    public record Reply(String text, List<List<Button>> buttons) {

        static Reply text(String text) {
            return new Reply(text, List.of());
        }
    }

    /** {@code scheduleId} is null when nothing was stored (the model failed, nothing readable). */
    public record Recognition(Long scheduleId, Reply reply) {
    }

    /** {@code edit} is the new content of the pressed message, null to leave it. */
    public record CallbackResult(boolean handled, String answerText, Reply edit) {

        static CallbackResult notHandled() {
            return new CallbackResult(false, null, null);
        }
    }

    private final StaffScheduleProperties properties;
    private final StaffScheduleRepository repository;
    private final StaffRoster roster;
    private final ModelGateway gateway;

    public StaffScheduleService(StaffScheduleProperties properties, StaffScheduleRepository repository,
                                StaffRoster roster, ModelGateway gateway) {
        this.properties = properties;
        this.repository = repository;
        this.roster = roster;
        this.gateway = gateway;
    }

    // ---- the picture ---------------------------------------------------------------------------------------------

    /** Empty when this picture was already recognised: the same update came twice. */
    public Optional<Recognition> recognize(byte[] image, String mimeType, long chatId, int messageId, String postedBy) {
        String tenant = properties.tenantOrDefault();
        if (repository.exists(tenant, chatId, messageId)) {
            return Optional.empty();
        }
        if (image == null || image.length == 0) {
            return Optional.of(new Recognition(null, Reply.text(StaffScheduleTexts.FAILED)));
        }
        if (image.length > properties.getMaxImageBytes()) {
            return Optional.of(new Recognition(null, Reply.text(StaffScheduleTexts.TOO_LARGE)));
        }
        LocalDate today = roster.today();
        ModelVisionResponse response;
        try {
            response = gateway.analyzeImage(ModelVisionRequest.of(
                            StaffScheduleParser.prompt(today),
                            Base64.getEncoder().encodeToString(image),
                            mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType,
                            null, "STAFF_SCHEDULE", "RECOGNIZE", "staff-schedule")
                    .withMaxTokens(properties.getMaxTokens()));
        } catch (RuntimeException e) {
            log.warn("Staff schedule: the vision model failed: {}", e.getClass().getSimpleName());
            return Optional.of(new Recognition(null, Reply.text(StaffScheduleTexts.MODEL_UNAVAILABLE)));
        }
        if (response == null || response.fallback() || response.text() == null || response.text().isBlank()) {
            log.warn("Staff schedule: no answer from the vision model (provider={})", response == null ? null : response.provider());
            return Optional.of(new Recognition(null, Reply.text(StaffScheduleTexts.MODEL_UNAVAILABLE)));
        }
        StaffScheduleParser.Result result = StaffScheduleParser.parse(response.text(), today);
        if (result.empty()) {
            log.info("Staff schedule: nothing readable (model={}, unreadable={}, skipped={})",
                    response.model(), result.unreadable(), result.skipped());
            return Optional.of(new Recognition(null, Reply.text(StaffScheduleTexts.UNREADABLE)));
        }

        List<StaffNameMatcher.Member> members = repository.activeMembers(tenant);
        Map<String, String> aliases = repository.aliases(tenant);
        Map<String, StaffNameMatcher.Match> matches = new HashMap<>();
        List<NewEntry> entries = new ArrayList<>();
        int ambiguous = 0;
        for (StaffScheduleParser.Shift shift : result.shifts()) {
            StaffNameMatcher.Match match = matches.computeIfAbsent(StaffNameMatcher.normalize(shift.name()),
                    name -> StaffNameMatcher.match(shift.name(), shift.role(), members, aliases));
            EntryStatus status = EntryStatus.valueOf(match.status().name());
            StaffNameMatcher.Member member = match.member();
            StaffRole role = shift.role() != null ? shift.role() : member == null ? null : member.role();
            List<String> candidates = status == EntryStatus.AMBIGUOUS
                    ? match.candidates().stream().map(StaffNameMatcher.Member::staffId).toList()
                    : List.of();
            if (status == EntryStatus.AMBIGUOUS) {
                ambiguous++;
            }
            entries.add(new NewEntry(shift.date(), shift.name(), role, shift.start(), shift.end(), status,
                    member == null ? null : member.staffId(), candidates));
        }
        Long id = repository.insert(tenant, chatId, messageId, ambiguous > 0 ? Status.CLARIFYING : Status.PROPOSED,
                result.firstDate(), result.lastDate(), result.unreadable(), postedBy, response.model(), entries);
        if (id == null) {
            return Optional.empty();
        }
        log.info("Staff schedule {} recognised: days={}, rows={}, ambiguous rows={}, unreadable={}, skipped={}, model={}",
                id, result.shifts().stream().map(StaffScheduleParser.Shift::date).distinct().count(), entries.size(),
                ambiguous, result.unreadable(), result.skipped(), response.model());
        return Optional.of(new Recognition(id, render(id)));
    }

    /** The bot's message about the schedule: the one the buttons are on and that is edited as they are pressed. */
    public void replySent(long scheduleId, int messageId) {
        repository.replyMessage(scheduleId, messageId);
    }

    // ---- the buttons ---------------------------------------------------------------------------------------------

    public CallbackResult callback(String data, Long chatId, String by) {
        if (data == null || !data.startsWith(CALLBACK_PREFIX)) {
            return CallbackResult.notHandled();
        }
        if (!properties.isScheduleChat(chatId)) {
            return new CallbackResult(true, "Эта кнопка работает только в чате персонала.", null);
        }
        Matcher pick = PICK.matcher(data);
        if (pick.matches()) {
            return pick(Long.parseLong(pick.group(1)), pick.group(2), chatId, by);
        }
        Matcher decision = DECISION.matcher(data);
        if (decision.matches()) {
            return decide(Long.parseLong(decision.group(2)), "ok".equals(decision.group(1)), chatId, by);
        }
        return new CallbackResult(true, "Кнопка устарела.", null);
    }

    private CallbackResult pick(long entryId, String choice, Long chatId, String by) {
        Entry entry = repository.entry(entryId).orElse(null);
        Schedule schedule = entry == null ? null : repository.schedule(entry.scheduleId()).orElse(null);
        if (schedule == null || schedule.chatId() != chatId) {
            return new CallbackResult(true, "Кнопка устарела.", null);
        }
        if (schedule.status() != Status.CLARIFYING || entry.status() != EntryStatus.AMBIGUOUS) {
            return new CallbackResult(true, "Уже уточнено.", render(schedule.id()));
        }
        String tenant = properties.tenantOrDefault();
        String name = StaffNameMatcher.normalize(entry.rawName());
        List<String> rawNames = repository.entries(schedule.id()).stream()
                .filter(other -> other.status() == EntryStatus.AMBIGUOUS && StaffNameMatcher.normalize(other.rawName()).equals(name))
                .map(Entry::rawName).distinct().toList();
        String answer;
        if ("x".equals(choice)) {
            rawNames.forEach(raw -> repository.dismiss(schedule.id(), raw));
            answer = "«" + entry.rawName() + "» — нет в справочнике, поручения не уйдут.";
        } else {
            int index = Integer.parseInt(choice);
            StaffNameMatcher.Member member = index < entry.candidates().size()
                    ? members(tenant).get(entry.candidates().get(index)) : null;
            if (member == null) {
                return new CallbackResult(true, "Этого сотрудника уже нет в справочнике.", render(schedule.id()));
            }
            rawNames.forEach(raw -> repository.resolve(schedule.id(), raw, member.staffId(), member.role()));
            repository.saveAlias(tenant, name, member.staffId(), by);
            log.info("AUDIT Staff schedule {}: a name settled as {} by {}", schedule.id(), member.staffId(), by);
            answer = "«" + entry.rawName() + "» — " + member.displayName() + ".";
        }
        boolean open = repository.entries(schedule.id()).stream().anyMatch(other -> other.status() == EntryStatus.AMBIGUOUS);
        if (!open) {
            repository.transition(schedule.id(), Status.CLARIFYING, Status.PROPOSED, null);
        }
        return new CallbackResult(true, answer, render(schedule.id()));
    }

    private CallbackResult decide(long scheduleId, boolean confirm, Long chatId, String by) {
        Schedule schedule = repository.schedule(scheduleId).orElse(null);
        if (schedule == null || schedule.chatId() != chatId) {
            return new CallbackResult(true, "Кнопка устарела.", null);
        }
        if (confirm) {
            if (!repository.transition(scheduleId, Status.PROPOSED, Status.CONFIRMED, by)) {
                String answer = switch (schedule.status()) {
                    case CONFIRMED -> "Уже подтверждено.";
                    case REJECTED -> "График не принят — пришлите новое фото.";
                    default -> "Сначала уточните имена.";
                };
                return new CallbackResult(true, answer, render(scheduleId));
            }
            log.info("AUDIT Staff schedule {} ({}) confirmed by {}", scheduleId, StaffScheduleTexts.range(schedule), by);
            return new CallbackResult(true, "График принят.", render(scheduleId));
        }
        boolean rejected = repository.transition(scheduleId, Status.PROPOSED, Status.REJECTED, by)
                || repository.transition(scheduleId, Status.CLARIFYING, Status.REJECTED, by);
        if (!rejected) {
            return new CallbackResult(true, schedule.status() == Status.REJECTED ? "Уже отклонён."
                    : "График уже подтверждён — пришлите новое фото, оно его заменит.", render(scheduleId));
        }
        log.info("AUDIT Staff schedule {} ({}) rejected by {}", scheduleId, StaffScheduleTexts.range(schedule), by);
        return new CallbackResult(true, "График не принят.", render(scheduleId));
    }

    /** The message for the schedule as it stands: the question while names are open, then the summary. */
    Reply render(long scheduleId) {
        Schedule schedule = repository.schedule(scheduleId).orElseThrow();
        List<Entry> entries = repository.entries(scheduleId);
        Map<String, StaffNameMatcher.Member> members = members(schedule.tenant());
        Map<String, String> names = new HashMap<>();
        members.forEach((id, member) -> names.put(id, member.displayName()));
        if (schedule.status() == Status.CLARIFYING) {
            Map<String, List<Entry>> byName = new LinkedHashMap<>();
            for (Entry entry : entries) {
                if (entry.status() == EntryStatus.AMBIGUOUS) {
                    byName.computeIfAbsent(StaffNameMatcher.normalize(entry.rawName()), name -> new ArrayList<>()).add(entry);
                }
            }
            List<List<Entry>> ambiguous = new ArrayList<>(byName.values());
            List<List<Button>> buttons = new ArrayList<>();
            for (int i = 0; i < ambiguous.size(); i++) {
                Entry first = ambiguous.get(i).get(0);
                List<Button> row = new ArrayList<>();
                for (int c = 0; c < first.candidates().size(); c++) {
                    String label = StaffScheduleTexts.shortName(names.getOrDefault(first.candidates().get(c), first.candidates().get(c)));
                    row.add(new Button((i + 1) + " · " + label, CALLBACK_PREFIX + "pick:" + first.id() + ":" + c));
                }
                row.add(new Button((i + 1) + " · никто", CALLBACK_PREFIX + "pick:" + first.id() + ":x"));
                buttons.add(row);
            }
            buttons.add(List.of(new Button("✏️ Исправить", CALLBACK_PREFIX + "fix:" + scheduleId)));
            return new Reply(StaffScheduleTexts.clarifying(schedule, ambiguous, names), buttons);
        }
        String text = StaffScheduleTexts.summary(schedule, entries, names, roster.today());
        if (schedule.status() == Status.PROPOSED) {
            return new Reply(text, List.of(List.of(
                    new Button("✅ Верно", CALLBACK_PREFIX + "ok:" + scheduleId),
                    new Button("✏️ Исправить", CALLBACK_PREFIX + "fix:" + scheduleId))));
        }
        return Reply.text(text);
    }

    // ---- /смена --------------------------------------------------------------------------------------------------

    public boolean isShiftCommand(String text) {
        return text != null && COMMAND.matcher(text.trim()).matches();
    }

    /** /смена — who is on shift now; /смена завтра (сегодня, 17.10) — the whole day. */
    public String shiftCommand(String text) {
        Matcher command = COMMAND.matcher(text == null ? "" : text.trim());
        String args = command.matches() && command.group(2) != null ? command.group(2).trim().toLowerCase(Locale.ROOT) : "";
        ZonedDateTime now = roster.now();
        LocalDate day = switch (args) {
            case "" -> now.toLocalDate();
            case "сегодня", "today" -> now.toLocalDate();
            case "завтра", "tomorrow" -> now.toLocalDate().plusDays(1);
            default -> StaffScheduleParser.date(args, now.toLocalDate());
        };
        if (day == null) {
            return "Не понял день. /смена — кто сейчас; /смена завтра; /смена 17.10.";
        }
        String tenant = properties.tenantOrDefault();
        Optional<Schedule> governing = repository.governing(tenant, day);
        Optional<Schedule> newest = repository.newest(tenant, day);
        String waiting = newest
                .filter(schedule -> schedule.status() == Status.PROPOSED || schedule.status() == Status.CLARIFYING)
                .filter(schedule -> governing.isEmpty() || schedule.id() > governing.get().id())
                .map(schedule -> "\n" + StaffScheduleTexts.waiting(schedule))
                .orElse("");
        if (governing.isEmpty()) {
            return StaffScheduleTexts.noSchedule(day) + waiting;
        }
        String answer = args.isEmpty()
                ? StaffScheduleTexts.nowOnShift(day, now.toLocalTime(), roster.onShiftAt(now))
                : StaffScheduleTexts.dayOnShift(day, roster.onShift(day));
        return answer + waiting;
    }

    private Map<String, StaffNameMatcher.Member> members(String tenant) {
        Map<String, StaffNameMatcher.Member> members = new HashMap<>();
        repository.activeMembers(tenant).forEach(member -> members.put(member.staffId(), member));
        return members;
    }
}
