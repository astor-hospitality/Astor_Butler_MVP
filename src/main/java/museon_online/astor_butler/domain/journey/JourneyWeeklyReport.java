package museon_online.astor_butler.domain.journey;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The Tuesday report for the manager (spec §5): last week's visits, how many came back, guest messages per visit and the
 * contest's 40, frequent questions, table requests and opt-outs. Once a week, from 10:00 venue time.
 */
public class JourneyWeeklyReport {

    private static final Map<String, String> TOPICS = Map.of("PARKING", "парковка", "KIDS", "дети", "WIFI", "Wi-Fi",
            "WINE", "вино", "EVENTS", "афиша");

    private final boolean enabled;
    private final GuestVisitRepository visits;
    private final Consumer<String> sender;
    private final Predicate<String> firstThisWeek;
    private final Clock clock;
    private final ZoneId zone;

    public JourneyWeeklyReport(boolean enabled, GuestVisitRepository visits, Consumer<String> sender, Predicate<String> firstThisWeek,
                               Clock clock, String zoneId) {
        this.enabled = enabled;
        this.visits = visits;
        this.sender = sender;
        this.firstThisWeek = firstThisWeek;
        this.clock = clock;
        this.zone = ZoneId.of(zoneId);
    }

    public void tick() {
        if (!enabled) {
            return;
        }
        ZonedDateTime now = clock.instant().atZone(zone);
        if (now.getDayOfWeek() != DayOfWeek.TUESDAY || now.getHour() < 10) {
            return;
        }
        if (!firstThisWeek.test("journey-weekly-report:" + now.toLocalDate())) {
            return;
        }
        sender.accept(text(now.toInstant().minus(Duration.ofDays(7)), now.toInstant()));
    }

    String text(Instant from, Instant to) {
        List<GuestVisit> week = visits.openedBetween(from, to);
        int total = week.size();
        long returned = week.stream().filter(visit -> visit.returnedVisitId() != null).count();
        long average = total == 0 ? 0 : Math.round(week.stream().mapToInt(GuestVisit::guestMessages).sum() / (double) total);
        long forty = week.stream().filter(visit -> visit.guestMessages() >= 40).count();
        Map<String, Long> questions = visits.eventsOfVisitsOpenedBetween(VisitEventKind.VENUE_QUESTION, from, to).stream()
                .collect(Collectors.groupingBy(event -> TOPICS.getOrDefault(event.note(), "другое"), LinkedHashMap::new, Collectors.counting()));
        String top = questions.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3)
                .map(Map.Entry::getKey).collect(Collectors.joining(", "));
        int requests = visits.eventsOfVisitsOpenedBetween(VisitEventKind.TABLE_REQUEST, from, to).size();
        int optOuts = visits.eventsOfVisitsOpenedBetween(VisitEventKind.OPTED_OUT, from, to).size();
        long percent = total == 0 ? 0 : Math.round(returned * 100.0 / total);
        return "📈 Путь гостя за неделю\n"
                + "Неделя: " + total + " " + visitsWord(total) + " · вернулись " + returned + " (" + percent + "%)"
                + " · сообщений гостя в среднем " + average + " · визитов ≥ 40: " + forty
                + " · частые вопросы: " + (top.isEmpty() ? "нет" : top)
                + " · просьб за столом: " + requests + " · отписок: " + optOuts;
    }

    static String visitsWord(int count) {
        int tens = count % 100;
        int ones = count % 10;
        if (tens >= 11 && tens <= 14) {
            return "визитов";
        }
        return switch (ones) {
            case 1 -> "визит";
            case 2, 3, 4 -> "визита";
            default -> "визитов";
        };
    }
}
