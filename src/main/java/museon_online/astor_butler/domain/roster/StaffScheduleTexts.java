package museon_online.astor_butler.domain.roster;

import museon_online.astor_butler.domain.roster.StaffScheduleRepository.Entry;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.EntryStatus;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.Schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the bot says in the «График» topic and to /смена. HTML, as every staff notice; names are escaped. */
final class StaffScheduleTexts {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    static final String MODEL_UNAVAILABLE =
            "Не получилось распознать график: модель сейчас недоступна. Пришлите фото ещё раз позже.";
    static final String UNREADABLE =
            "Не смог прочитать график. Пришлите фото ровнее и крупнее или скрин таблицы.";
    static final String TOO_LARGE = "Картинка слишком большая для распознавания. Пришлите фото или скрин поменьше.";
    static final String FAILED = "Не получилось распознать график. Пришлите фото ещё раз.";

    private StaffScheduleTexts() {
    }

    static String clarifying(Schedule schedule, List<List<Entry>> ambiguous, Map<String, String> names) {
        StringBuilder text = new StringBuilder(title(schedule)).append(": уточните, кто это — не хочу угадывать.\n");
        for (int i = 0; i < ambiguous.size(); i++) {
            Entry entry = ambiguous.get(i).get(0);
            text.append('\n').append(i + 1).append(". «").append(escape(entry.rawName())).append('»');
            if (entry.role() != null) {
                text.append(" (").append(entry.role().label()).append(')');
            }
            text.append(", смен: ").append(ambiguous.get(i).size()).append(" — ");
            List<String> options = new ArrayList<>();
            for (String staffId : entry.candidates()) {
                options.add(escape(names.getOrDefault(staffId, staffId)));
            }
            text.append(String.join(" или ", options)).append('?');
        }
        return text.append("\n\nВыбор запомню: в следующем графике это имя спрашивать не буду.").toString();
    }

    /** The summary under «Верно / Исправить»: the day that matters most (today if the schedule has it) by role. */
    static String summary(Schedule schedule, List<Entry> entries, Map<String, String> names, LocalDate today) {
        StringBuilder text = new StringBuilder(title(schedule)).append('\n');
        LocalDate day = focusDay(schedule, today);
        text.append(day.equals(today) ? "На сегодня" : "На " + DAY.format(day)).append(": ");
        Map<StaffRole, List<String>> byRole = new EnumMap<>(StaffRole.class);
        for (Entry entry : entries) {
            if (entry.date().equals(day) && entry.status() == EntryStatus.MATCHED && entry.role() != null) {
                byRole.computeIfAbsent(entry.role(), role -> new ArrayList<>())
                        .add(escape(names.getOrDefault(entry.staffId(), entry.rawName())));
            }
        }
        text.append(byRole.isEmpty() ? "в графике никого" : roles(byRole)).append('.');

        Set<String> unknown = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.status() == EntryStatus.UNKNOWN) {
                unknown.add(escape(entry.rawName()) + (entry.role() == null ? "" : " (" + entry.role().label() + ")"));
            }
        }
        if (!unknown.isEmpty()) {
            text.append("\nНет в справочнике: ").append(String.join(", ", unknown)).append(" — им поручения не уйдут.");
        }
        if (schedule.unreadable() > 0) {
            text.append("\nНе разобрал строк: ").append(schedule.unreadable()).append(" — проверьте фото.");
        }
        long days = entries.stream().map(Entry::date).distinct().count();
        text.append("\nВсего: дней ").append(days).append(", смен ").append(entries.size()).append('.');
        switch (schedule.status()) {
            case PROPOSED -> text.append("\nПока не нажали «Верно», бот этот график не использует.");
            case CONFIRMED -> text.append("\n\n✅ Верно — ").append(escape(by(schedule))).append(". График действует.");
            case REJECTED -> text.append("\n\n✏️ Не принят — ").append(escape(by(schedule)))
                    .append(". Пришлите исправленное фото сюда же; пока действует прежний подтверждённый график.");
            default -> {
            }
        }
        return text.toString();
    }

    static String nowOnShift(LocalDate day, LocalTime now, List<StaffRoster.OnShift> shifts) {
        if (shifts.isEmpty()) {
            return "По графику сейчас (" + DAY.format(day) + ", " + TIME.format(now) + ") никого нет на смене.";
        }
        StringBuilder text = new StringBuilder("<b>Сейчас на смене</b> (" + DAY.format(day) + ", " + TIME.format(now) + "):");
        appendByRole(text, shifts, true);
        return text.toString();
    }

    static String dayOnShift(LocalDate day, List<StaffRoster.OnShift> shifts) {
        if (shifts.isEmpty()) {
            return "По графику на " + DAY.format(day) + " никого нет.";
        }
        StringBuilder text = new StringBuilder("<b>На смене " + DAY.format(day) + "</b>:");
        appendByRole(text, shifts, false);
        return text.toString();
    }

    static String noSchedule(LocalDate day) {
        return "Подтверждённого графика на " + DAY.format(day) + " нет. Пришлите фото графика в тему «График».";
    }

    static String waiting(Schedule schedule) {
        return "График " + range(schedule) + " ждёт «Верно» в теме «График».";
    }

    static String range(Schedule schedule) {
        return schedule.firstDate().equals(schedule.lastDate())
                ? DAY.format(schedule.firstDate())
                : DAY.format(schedule.firstDate()) + "–" + DAY.format(schedule.lastDate());
    }

    /** «Анна С.» for a button: the first word and the initial of the second. */
    static String shortName(String name) {
        String[] words = name.trim().split("\\s+");
        String text = words.length > 1 && !words[1].isEmpty() ? words[0] + " " + words[1].charAt(0) + "." : words[0];
        return text.length() > 24 ? text.substring(0, 24) : text;
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void appendByRole(StringBuilder text, List<StaffRoster.OnShift> shifts, boolean untilOnly) {
        Map<StaffRole, List<String>> byRole = new EnumMap<>(StaffRole.class);
        for (StaffRoster.OnShift shift : shifts) {
            String hours = untilOnly
                    ? (shift.end() == null ? "" : " (до " + TIME.format(shift.end()) + ")")
                    : hours(shift.start(), shift.end());
            byRole.computeIfAbsent(shift.role(), role -> new ArrayList<>()).add(escape(shift.displayName()) + hours);
        }
        byRole.forEach((role, people) -> text.append('\n')
                .append(role == null ? "без роли" : role.label(people.size())).append(" — ").append(String.join(", ", people)));
    }

    private static String hours(LocalTime start, LocalTime end) {
        if (start == null && end == null) {
            return "";
        }
        return " " + (start == null ? "…" : TIME.format(start)) + "–" + (end == null ? "…" : TIME.format(end));
    }

    private static String roles(Map<StaffRole, List<String>> byRole) {
        Map<String, String> parts = new LinkedHashMap<>();
        byRole.forEach((role, people) -> parts.put(role.name(), role.label(people.size()) + " " + String.join(", ", people)));
        return String.join("; ", parts.values());
    }

    private static LocalDate focusDay(Schedule schedule, LocalDate today) {
        if (!today.isBefore(schedule.firstDate()) && !today.isAfter(schedule.lastDate())) {
            return today;
        }
        return schedule.firstDate();
    }

    private static String title(Schedule schedule) {
        return "<b>График " + range(schedule) + "</b>";
    }

    private static String by(Schedule schedule) {
        return schedule.decidedBy() == null || schedule.decidedBy().isBlank() ? "сотрудник" : schedule.decidedBy();
    }
}
