package museon_online.astor_butler.domain.tips;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.roster.FloorZones;
import museon_online.astor_butler.domain.roster.TableWaiters;
import museon_online.astor_butler.domain.roster.WaiterZoneRepository;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The manager's tip commands (#161), handled the way {@code /manual} and {@code /stop} are: in the staff (hostess),
 * manager or admin chat only, by chat membership. A guest typing them is talking to the guest flow.
 *
 * <ul>
 *   <li>{@code /официант <стол> <имя|staff_id>} ({@code /waiter}) — the waiter of the table for today's shift;
 *       {@code 1-6} assigns a range of numeric tables, {@code /официант <стол> -} clears; {@code /официант} lists today;</li>
 *   <li>{@code /чаевые <staff_id|имя> <ссылка> [заметка]} ({@code /tips}) — the waiter's own tip link;
 *       {@code /чаевые <staff> -} clears it; {@code /чаевые} lists the waiters with «ссылка есть / нет».</li>
 *   <li>{@code /зона <ЗОНА|столы> <имя|staff_id>} ({@code /zone}) — the zone a waiter usually works (WINDOW, «окно»,
 *       or tables, «1-6», which name their zones); a zone may have several. {@code /зона <ЗОНА> <имя> -} takes it
 *       from that waiter, {@code /зона <ЗОНА> -} from everyone; {@code /зона} lists the zones. A table nobody was
 *       given for the day goes, on the guest's first request, to the waiter on shift whose zone holds it.</li>
 * </ul>
 * The bot never prints a link into the chat: the listing says only whether there is one. With the feature off the
 * commands are unknown.
 */
@Service
@Slf4j
public class TipsStaffCommandService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final Pattern RANGE = Pattern.compile("^(\\d{1,3})\\s*[-–—]\\s*(\\d{1,3})$");
    private static final Pattern TABLE_CODE = Pattern.compile("^[\\p{L}\\p{N}_-]{1,32}$");
    private static final Set<String> CLEAR_WORDS = Set.of("-", "—", "нет", "удалить", "снять", "убрать", "clear", "none", "off");
    private static final Set<String> TABLE_WORDS = Set.of("стол", "столы", "столу", "столам", "table", "tables");
    private static final int MAX_TABLES = 50;
    private static final int MAX_LINK_LENGTH = 2048;
    private static final int MAX_NOTE_LENGTH = 160;

    static final String WAITER_USAGE = "Назначить: /официант &lt;стол&gt; &lt;имя|staff_id&gt;; несколько столов: /официант 1-6 Даниил; "
            + "снять: /официант &lt;стол&gt; -; список на сегодня: /официант";
    static final String ZONE_USAGE = "Зона официанта: /зона &lt;ЗОНА|столы&gt; &lt;имя|staff_id&gt; (например /зона WINDOW Даниил "
            + "или /зона 1-6 Даниил); убрать у одного: /зона &lt;ЗОНА&gt; &lt;имя&gt; -; убрать у всех: /зона &lt;ЗОНА&gt; -; список: /зона";
    static final String TIPS_USAGE = "Задать ссылку: /чаевые &lt;staff_id|имя&gt; &lt;ссылка&gt; [заметка]; убрать: /чаевые &lt;staff_id&gt; -; "
            + "список: /чаевые. Ссылку бот в чат не выводит.";

    private final TipsPersonalProperties properties;
    private final TipsPersonalRepository directory;
    private final TableReservationNotificationService notificationService;
    private final TelegramSystemNotifier systemNotifier;
    private final Clock clock;

    private WaiterZoneRepository zones;
    private FloorZones floor;

    private String hostessChatId;
    private String managerChatId;
    private String adminChatId;

    @Autowired
    public TipsStaffCommandService(TipsPersonalProperties properties, TipsPersonalRepository directory,
                                   TableReservationNotificationService notificationService,
                                   TelegramSystemNotifier systemNotifier,
                                   @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                   @Value("${telegram.booking.manager-chat-id:876857557}") String managerChatId,
                                   @Value("${telegram.admin.chat-id:}") String adminChatId) {
        this(properties, directory, notificationService, systemNotifier, hostessChatId, managerChatId, adminChatId, Clock.systemUTC());
    }

    TipsStaffCommandService(TipsPersonalProperties properties, TipsPersonalRepository directory,
                            TableReservationNotificationService notificationService, TelegramSystemNotifier systemNotifier,
                            String hostessChatId, String managerChatId, String adminChatId, Clock clock) {
        this.properties = properties;
        this.directory = directory;
        this.notificationService = notificationService;
        this.systemNotifier = systemNotifier;
        this.hostessChatId = hostessChatId;
        this.managerChatId = managerChatId;
        this.adminChatId = adminChatId;
        this.clock = clock;
    }

    /** The standing zones for /зона; without them the command says it is not available. */
    @Autowired(required = false)
    public void setZones(WaiterZoneRepository zones, FloorZones floor) {
        this.zones = zones;
        this.floor = floor;
    }

    /** True when the message was one of the commands from a staff chat and has been answered. */
    public boolean handle(IncomingMessage incoming) {
        if (incoming == null || !properties.isEnabled()) {
            return false;
        }
        Command command = Command.parse(incoming.text());
        if (command == null) {
            return false;
        }
        if (!isStaffCommandChat(incoming.chatId())) {
            // A guest (or an unknown group) typing /официант is not the shift manager; the guest flow answers as usual.
            return false;
        }
        String reply;
        try {
            reply = switch (command.kind()) {
                case WAITER -> waiter(command.args(), incoming);
                case TIPS -> tips(command.args(), incoming);
                case ZONE -> zone(command.args(), incoming);
            };
        } catch (RuntimeException e) {
            log.warn("Tips staff command failed: {}", e.toString());
            reply = "Не получилось выполнить команду (" + escape(e.getClass().getSimpleName()) + "). Попробуйте ещё раз.";
        }
        notificationService.sendStaffNotice(incoming.chatId().toString(), reply);
        return true;
    }

    // ---- /официант -------------------------------------------------------------------------------------------------

    private String waiter(List<String> args, IncomingMessage incoming) {
        LocalDate today = today();
        if (args.isEmpty()) {
            return assignmentsToday(today);
        }
        List<String> rest = new ArrayList<>(args);
        if (TABLE_WORDS.contains(rest.get(0).toLowerCase(Locale.ROOT)) && rest.size() > 1) {
            rest.remove(0);
        }
        List<String> tables = tables(rest.get(0));
        if (tables == null) {
            return "Не понял стол «" + escape(rest.get(0)) + "». " + WAITER_USAGE;
        }
        List<String> nameWords = rest.subList(1, rest.size());
        if (nameWords.isEmpty()) {
            return "Кого назначить на " + tablesLabel(tables) + "? " + WAITER_USAGE;
        }
        if (nameWords.size() == 1 && CLEAR_WORDS.contains(nameWords.get(0).toLowerCase(Locale.ROOT))) {
            int removed = 0;
            for (String table : tables) {
                if (directory.unassign(properties.tenantOrDefault(), table, today)) {
                    removed++;
                }
            }
            return removed == 0
                    ? "На " + DAY.format(today) + " на " + tablesLabel(tables) + " никто не был назначен."
                    : "Снял назначение: " + tablesLabel(tables) + " на " + DAY.format(today) + ".";
        }
        Match match = findMember(nameWords);
        if (match.member() == null) {
            return match.ambiguous()
                    ? "Нашёл несколько: " + names(match.candidates()) + ". Укажите staff_id."
                    : "Не нашёл сотрудника «" + escape(String.join(" ", nameWords)) + "» в справочнике. Список: /чаевые";
        }
        TipStaffMember member = match.member();
        String by = who(incoming);
        for (String table : tables) {
            directory.assign(properties.tenantOrDefault(), table, today, member.staffId(), by);
        }
        log.info("AUDIT Официант {} назначен на {} ({}) — {}", member.staffId(), tablesLabel(tables), today, by);
        String text = capitalize(tablesLabel(tables)) + " на " + DAY.format(today) + " — " + name(member) + ".";
        if (!member.hasTipLink()) {
            text += "\nСсылки для чаевых у " + escape(member.displayName()) + " нет — чаевые пойдут получателю по умолчанию. "
                    + "Задать: /чаевые " + escape(member.staffId()) + " &lt;ссылка&gt;";
        }
        return text;
    }

    private String assignmentsToday(LocalDate today) {
        List<TableWaiterAssignment> assignments = directory.assignments(properties.tenantOrDefault(), today);
        if (assignments.isEmpty()) {
            return "На сегодня (" + DAY.format(today) + ") официанты столам не назначены.\n" + WAITER_USAGE;
        }
        StringBuilder text = new StringBuilder("<b>Официанты по столам на " + DAY.format(today) + "</b>\n");
        for (TableWaiterAssignment assignment : assignments) {
            text.append("стол ").append(escape(assignment.tableCode())).append(" — ")
                    .append(escape(assignment.displayName())).append(" (").append(escape(assignment.staffId())).append(")")
                    .append(TableWaiters.AUTO.equals(assignment.assignedBy()) ? " · авто, по зоне" : "").append("\n");
        }
        return text.append("\n").append(WAITER_USAGE).toString();
    }

    /** «5», «1-6» (numeric tables only), «1,2,5»; null when the words are not tables. */
    static List<String> tables(String spec) {
        Set<String> tables = new LinkedHashSet<>();
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            Matcher range = RANGE.matcher(token);
            if (range.matches()) {
                int from = Integer.parseInt(range.group(1));
                int to = Integer.parseInt(range.group(2));
                if (from < 1 || to < from || to - from >= MAX_TABLES) {
                    return null;
                }
                for (int table = from; table <= to; table++) {
                    tables.add(String.valueOf(table));
                }
                continue;
            }
            if (!TABLE_CODE.matcher(token).matches()) {
                return null;
            }
            tables.add(token.matches("\\d+") ? String.valueOf(Integer.parseInt(token)) : token);
        }
        if (tables.isEmpty() || tables.size() > MAX_TABLES) {
            return null;
        }
        return new ArrayList<>(tables);
    }

    private static String tablesLabel(List<String> tables) {
        if (tables.size() == 1) {
            return "стол " + escape(tables.get(0));
        }
        return "столы " + tables.stream().map(TipsStaffCommandService::escape).reduce((a, b) -> a + ", " + b).orElse("");
    }

    // ---- /зона ----------------------------------------------------------------------------------------------------

    /** The zones a /зона names: a zone word, or tables whose zones they are; a problem to say instead. */
    record ZoneSpec(List<String> zones, String problem) {
    }

    private String zone(List<String> args, IncomingMessage incoming) {
        if (zones == null || floor == null) {
            return "Зоны официантов сейчас недоступны.";
        }
        String tenant = properties.tenantOrDefault();
        if (args.isEmpty()) {
            return zonesList(tenant);
        }
        List<String> rest = new ArrayList<>(args);
        if (TABLE_WORDS.contains(rest.get(0).toLowerCase(Locale.ROOT)) && rest.size() > 1) {
            rest.remove(0);
        }
        ZoneSpec spec = zoneSpec(rest.get(0));
        if (spec.problem() != null) {
            return spec.problem();
        }
        List<String> nameWords = new ArrayList<>(rest.subList(1, rest.size()));
        boolean clear = !nameWords.isEmpty() && CLEAR_WORDS.contains(nameWords.get(nameWords.size() - 1).toLowerCase(Locale.ROOT));
        if (clear) {
            nameWords.remove(nameWords.size() - 1);
        }
        String by = who(incoming);
        String zonesLabel = zonesLabel(spec.zones());
        if (nameWords.isEmpty()) {
            if (!clear) {
                return "Кого поставить в зону " + zonesLabel + "? " + ZONE_USAGE;
            }
            int removed = 0;
            for (String zone : spec.zones()) {
                removed += zones.clearZone(tenant, zone);
            }
            log.info("AUDIT Зона {} снята со всех официантов — {}", spec.zones(), by);
            return removed == 0 ? "В зоне " + zonesLabel + " никто не стоял." : "Зона " + zonesLabel + ": убрал всех (" + removed + ").";
        }
        Match match = findMember(nameWords);
        if (match.member() == null) {
            return match.ambiguous()
                    ? "Нашёл несколько: " + names(match.candidates()) + ". Укажите staff_id."
                    : "Не нашёл сотрудника «" + escape(String.join(" ", nameWords)) + "» в справочнике. Список: /чаевые";
        }
        TipStaffMember member = match.member();
        if (clear) {
            boolean removed = false;
            for (String zone : spec.zones()) {
                removed |= zones.remove(tenant, member.staffId(), zone);
            }
            log.info("AUDIT Официант {} убран из зоны {} — {}", member.staffId(), spec.zones(), by);
            return removed ? name(member) + " больше не в зоне " + zonesLabel + "." : name(member) + " и не был в зоне " + zonesLabel + ".";
        }
        for (String zone : spec.zones()) {
            zones.add(tenant, member.staffId(), zone, by);
        }
        log.info("AUDIT Официант {} поставлен в зону {} — {}", member.staffId(), spec.zones(), by);
        StringBuilder text = new StringBuilder(name(member)).append(" — зона ").append(zonesLabel).append(": ");
        text.append(spec.zones().stream().map(zone -> tablesOf(zone)).reduce((a, b) -> a + "; " + b).orElse("")).append('.');
        text.append("\nКогда стол из зоны на сегодня никому не дан (/официант), просьба гостя уйдёт официанту этой зоны, если он на смене по графику.");
        if (!"WAITER".equals(member.role())) {
            text.append("\n").append(escape(member.displayName())).append(" в справочнике не официант — по зоне выбираются только официанты на смене.");
        }
        return text.toString();
    }

    private ZoneSpec zoneSpec(String word) {
        String zone = floor.zone(word);
        if (zone != null) {
            return new ZoneSpec(List.of(zone), null);
        }
        List<String> tables = tables(word);
        if (tables == null) {
            return new ZoneSpec(null, "Не понял зону «" + escape(word) + "». Зоны: " + String.join(", ", floor.zones()) + ". " + ZONE_USAGE);
        }
        Set<String> found = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String table : tables) {
            floor.zoneOfTable(table).ifPresentOrElse(found::add, () -> unknown.add(table));
        }
        if (!unknown.isEmpty() && unknown.stream().anyMatch(table -> !table.matches("\\d+"))) {
            return new ZoneSpec(null, "Не понял зону «" + escape(word) + "». Зоны: " + String.join(", ", floor.zones()) + ". " + ZONE_USAGE);
        }
        if (!unknown.isEmpty()) {
            return new ZoneSpec(null, "На плане зала нет: " + tablesLabel(unknown) + ". " + ZONE_USAGE);
        }
        return new ZoneSpec(new ArrayList<>(found), null);
    }

    private String zonesList(String tenant) {
        List<WaiterZoneRepository.WaiterZone> standing = zones.all(tenant);
        Set<String> listed = new LinkedHashSet<>(floor.zones());
        standing.forEach(row -> listed.add(row.zone()));
        StringBuilder text = new StringBuilder("<b>Зоны официантов</b>\n");
        for (String zone : listed) {
            List<String> who = standing.stream().filter(row -> zone.equals(row.zone()))
                    .map(row -> escape(row.displayName())).toList();
            text.append("• ").append(escape(FloorZones.label(zone))).append(" · ").append(tablesOf(zone)).append(" — ")
                    .append(who.isEmpty() ? "никого" : String.join(", ", who)).append("\n");
        }
        return text.append("\nСтол без официанта на сегодня уходит официанту на смене из его зоны; нет такого — команде.\n")
                .append(ZONE_USAGE).toString();
    }

    private String tablesOf(String zone) {
        List<String> tables = floor.tablesOf(zone);
        return tables.isEmpty() ? "столов нет на плане" : tablesLabel(tables);
    }

    private static String zonesLabel(List<String> zones) {
        return zones.stream().map(zone -> escape(FloorZones.label(zone)))
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    // ---- /чаевые ---------------------------------------------------------------------------------------------------

    private String tips(List<String> args, IncomingMessage incoming) {
        if (args.isEmpty()) {
            return waitersWithLinks();
        }
        int linkAt = -1;
        for (int i = 0; i < args.size(); i++) {
            String word = args.get(i).toLowerCase(Locale.ROOT);
            if (word.startsWith("http://") || word.startsWith("https://") || CLEAR_WORDS.contains(word)) {
                linkAt = i;
                break;
            }
        }
        List<String> nameWords = linkAt < 0 ? args : args.subList(0, linkAt);
        if (nameWords.isEmpty()) {
            return "Чью ссылку задать? " + TIPS_USAGE;
        }
        Match match = findMember(nameWords);
        if (match.member() == null) {
            return match.ambiguous()
                    ? "Нашёл несколько: " + names(match.candidates()) + ". Укажите staff_id."
                    : "Не нашёл сотрудника «" + escape(String.join(" ", nameWords)) + "» в справочнике. " + TIPS_USAGE;
        }
        TipStaffMember member = match.member();
        if (linkAt < 0) {
            return linkStatus(member);
        }
        String linkWord = args.get(linkAt);
        String note = String.join(" ", args.subList(linkAt + 1, args.size())).trim();
        if (note.length() > MAX_NOTE_LENGTH) {
            note = note.substring(0, MAX_NOTE_LENGTH);
        }
        String by = who(incoming);
        if (CLEAR_WORDS.contains(linkWord.toLowerCase(Locale.ROOT))) {
            directory.setTipLink(properties.tenantOrDefault(), member.staffId(), null, null);
            audit("Ссылка для чаевых удалена: " + member.displayName() + " (" + member.staffId() + ") — " + by);
            return "Ссылка для чаевых: " + name(member) + " — удалена.";
        }
        String problem = linkProblem(linkWord);
        if (problem != null) {
            return problem;
        }
        directory.setTipLink(properties.tenantOrDefault(), member.staffId(), linkWord.trim(), note);
        audit("Ссылка для чаевых задана: " + member.displayName() + " (" + member.staffId() + ") — " + by);
        return "Ссылка для чаевых: " + name(member) + " — сохранена" + (note.isBlank() ? "." : ". Заметка: " + escape(note))
                + "\nГость увидит её после «да» на чаевых за столом этого официанта.";
    }

    private String waitersWithLinks() {
        List<TipStaffMember> members = directory.activeMembers(properties.tenantOrDefault());
        List<TipStaffMember> waiters = members.stream().filter(member -> "WAITER".equals(member.role())).toList();
        if (waiters.isEmpty()) {
            return "В справочнике нет активных официантов (astor_staff_members, роль WAITER). " + TIPS_USAGE;
        }
        StringBuilder text = new StringBuilder("<b>Ссылки для чаевых</b>\n");
        for (TipStaffMember waiter : waiters) {
            text.append("• ").append(linkStatus(waiter)).append("\n");
        }
        return text.append("\n").append(TIPS_USAGE).toString();
    }

    /** «Даниил (daniil) — ссылка есть · Сбер»; the link itself is never part of it. */
    private static String linkStatus(TipStaffMember member) {
        String status = member.hasTipLink() ? "ссылка есть" : "ссылки нет";
        String note = member.tipNote() == null || member.tipNote().isBlank() ? "" : " · " + escape(member.tipNote());
        return name(member) + " — " + status + note;
    }

    /** Only an https link without spaces: that is what SBP and bank pages give; anything else is a typo or a paste gone wrong. */
    static String linkProblem(String link) {
        String value = link == null ? "" : link.trim();
        if (value.length() > MAX_LINK_LENGTH) {
            return "Ссылка слишком длинная (больше " + MAX_LINK_LENGTH + " символов).";
        }
        if (!value.toLowerCase(Locale.ROOT).startsWith("https://") || value.length() < "https://x.y".length()) {
            return "Нужна ссылка вида https://… — такая, какую даёт СБП или банк. Ссылку бот в чат не выводит.";
        }
        if (value.chars().anyMatch(Character::isWhitespace) || value.chars().anyMatch(c -> c < 0x21)) {
            return "В ссылке не должно быть пробелов.";
        }
        return null;
    }

    // ---- directory lookup ------------------------------------------------------------------------------------------

    record Match(TipStaffMember member, List<TipStaffMember> candidates) {
        boolean ambiguous() {
            return member == null && candidates.size() > 1;
        }
    }

    /**
     * Exact {@code staff_id} first, then the exact name, then every word of the words matching a word of the name by
     * stem («Даниилу» → «Даниил»); two people fitting equally is nobody, as in the voice tasks.
     */
    Match findMember(List<String> words) {
        String joined = String.join(" ", words).trim().toLowerCase(Locale.ROOT);
        List<TipStaffMember> members = directory.activeMembers(properties.tenantOrDefault());
        for (TipStaffMember member : members) {
            if (member.staffId() != null && member.staffId().toLowerCase(Locale.ROOT).equals(joined)) {
                return new Match(member, List.of(member));
            }
        }
        List<TipStaffMember> byName = members.stream()
                .filter(member -> member.displayName() != null && member.displayName().trim().toLowerCase(Locale.ROOT).equals(joined))
                .toList();
        if (byName.size() == 1) {
            return new Match(byName.get(0), byName);
        }
        if (byName.size() > 1) {
            return new Match(null, byName);
        }
        List<TipStaffMember> byStem = members.stream().filter(member -> allWordsMatch(member, words)).toList();
        return byStem.size() == 1 ? new Match(byStem.get(0), byStem) : new Match(null, byStem);
    }

    private static boolean allWordsMatch(TipStaffMember member, List<String> words) {
        List<String> nameWords = words(member.displayName());
        String id = member.staffId() == null ? "" : member.staffId().toLowerCase(Locale.ROOT);
        for (String raw : words) {
            String word = raw.toLowerCase(Locale.ROOT);
            boolean matched = word.equals(id);
            for (String part : nameWords) {
                if (part.equals(word) || stems(word, part)) {
                    matched = true;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return !words.isEmpty();
    }

    /** «Анне» and «Анна» are one name in two cases; «Марии» is not «Марина» (same rule as {@code StaffAssignees}). */
    static boolean stems(String a, String b) {
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        if (common < 3 || common < Math.min(a.length(), b.length()) - 2) {
            return false;
        }
        return ending(a.substring(common)) && ending(b.substring(common));
    }

    private static boolean ending(String suffix) {
        if (suffix.length() > 2) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if ("аеёиоуыэюяй".indexOf(suffix.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}-]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.zone()));
    }

    private void audit(String line) {
        log.info("AUDIT {}", line);
        try {
            systemNotifier.sendSystemNotice(line);
        } catch (RuntimeException e) {
            log.debug("Tips audit line was not sent to the system chat: {}", e.toString());
        }
    }

    private static String names(List<TipStaffMember> members) {
        return members.stream().map(TipsStaffCommandService::name).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String name(TipStaffMember member) {
        return escape(member.displayName()) + " (" + escape(member.staffId()) + ")";
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** The manager's name as the audit line says it: first and last name, else @username, else the Telegram id. */
    private static String who(IncomingMessage incoming) {
        String first = incoming.firstName() == null ? "" : incoming.firstName().strip();
        String last = incoming.lastName() == null ? "" : incoming.lastName().strip();
        String name = (first + " " + last).strip();
        String username = incoming.username() == null || incoming.username().isBlank() ? "" : "@" + incoming.username().strip();
        if (!name.isBlank()) {
            return username.isBlank() ? name : name + " (" + username + ")";
        }
        if (!username.isBlank()) {
            return username;
        }
        return incoming.telegramUserId() == null ? "неизвестно" : "id " + incoming.telegramUserId();
    }

    /** Only the configured staff chats may give these commands: the same chats as for {@code /manual} and {@code /stop}. */
    private boolean isStaffCommandChat(Long chatId) {
        return sameChat(hostessChatId, chatId) || sameChat(managerChatId, chatId) || sameChat(adminChatId, chatId);
    }

    private static boolean sameChat(String configured, Long chatId) {
        return chatId != null && configured != null && !configured.isBlank() && configured.equals(chatId.toString());
    }

    static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** {@code /официант}, {@code /waiter}; {@code /чаевые}, {@code /tips} — with or without {@code @bot}, with the rest as words. */
    record Command(Kind kind, List<String> args) {

        enum Kind { WAITER, TIPS, ZONE }

        static Command parse(String text) {
            String normalized = text == null ? "" : text.strip();
            if (!normalized.startsWith("/")) {
                return null;
            }
            String[] parts = normalized.split("\\s+");
            String command = parts[0].toLowerCase(Locale.ROOT);
            int mention = command.indexOf('@');
            if (mention > 0) {
                command = command.substring(0, mention);
            }
            Kind kind = switch (command) {
                case "/официант", "/waiter" -> Kind.WAITER;
                case "/чаевые", "/tips" -> Kind.TIPS;
                case "/зона", "/зоны", "/zone", "/zones" -> Kind.ZONE;
                default -> null;
            };
            if (kind == null) {
                return null;
            }
            return new Command(kind, List.copyOf(Arrays.asList(parts).subList(1, parts.length)));
        }
    }
}
