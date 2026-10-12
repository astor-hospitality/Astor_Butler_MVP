package museon_online.astor_butler.domain.shift;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.glasses.tasks.StaffTask;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the open-status summary behind {@code /stop} and {@code /status_butler} (#134) from the same tables the shift
 * briefing reads, and formats it for the staff chat. Bookings are looked at from today for {@value #DAYS_AHEAD} days:
 * an open request for next week is still something the team now has to answer by hand.
 */
@Service
@Slf4j
public class ButlerPauseSummaryService {

    static final int DAYS_AHEAD = 14;
    static final int LIMIT = 500;
    /** Lines per section; the rest is counted so the message stays readable and under Telegram's length limit. */
    static final int LINES = 15;

    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TableReservationRepository reservations;
    private final GuestBillRepository bills;
    private final JdbcTemplate jdbcTemplate;
    private final ShiftBriefingProperties properties;
    private final boolean billingEnabled;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private Clock clock = Clock.systemUTC();

    public ButlerPauseSummaryService(TableReservationRepository reservations, GuestBillRepository bills,
                                     JdbcTemplate jdbcTemplate, ShiftBriefingProperties properties,
                                     @Value("${astor.billing.enabled:false}") boolean billingEnabled) {
        this.reservations = reservations;
        this.bills = bills;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.billingEnabled = billingEnabled;
    }

    public ButlerPauseSummary build() {
        ZoneId zone = properties.zone();
        String venue = properties.getVenueCode();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(DAYS_AHEAD).atStartOfDay(zone).toInstant();

        List<ButlerPauseSummary.Booking> awaiting = new ArrayList<>();
        List<ButlerPauseSummary.Booking> confirmed = new ArrayList<>();
        List<ButlerPauseSummary.Booking> inPresto = new ArrayList<>();
        List<ButlerPauseSummary.Lunch> lunches = new ArrayList<>();
        for (TableReservationOrder order : reservations.findOrdersStartingBetween(venue, from, to, LIMIT)) {
            boolean open = order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                    || order.status() == TableReservationStatus.CONFIRMED
                    || order.status() == TableReservationStatus.SEATED;
            if (!open) {
                continue;
            }
            boolean presto = order.sbisExternalId() != null && !order.sbisExternalId().isBlank();
            boolean lunch = isLunch(order);
            ButlerPauseSummary.Booking booking = new ButlerPauseSummary.Booking(order.id(),
                    DAY_TIME.format(LocalDateTime.ofInstant(order.requestedStartAt(), zone)), name(order), table(order),
                    party(order), lunch);
            if (presto) {
                inPresto.add(booking);
            } else if (order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
                awaiting.add(booking);
            } else {
                confirmed.add(booking);
            }
            if (lunch && order.requestedStartAt().isBefore(tomorrow)) {
                lunches.add(new ButlerPauseSummary.Lunch(order.id(),
                        TIME.format(LocalDateTime.ofInstant(order.requestedStartAt(), zone)), name(order), table(order),
                        party(order), presto ? "в Presto" : lunchStatus(order.status()),
                        composition(order)));
            }
        }

        return new ButlerPauseSummary(today, venue, List.copyOf(awaiting), List.copyOf(confirmed), List.copyOf(inPresto),
                List.copyOf(lunches), billsToday(venue, from, tomorrow), openStaffTasks());
    }

    /** The summary as the staff chat sees it: HTML, guest names and tables, never a phone. */
    public String format(ButlerPauseSummary summary) {
        StringBuilder text = new StringBuilder();
        text.append("<b>Открытые статусы на ").append(DATE.format(summary.date())).append("</b>\n");

        text.append("\n<b>Заявки на бронь</b> (сегодня и ").append(DAYS_AHEAD).append(" дней вперёд)\n");
        section(text, "ждут хостес", summary.awaitingHostess());
        section(text, "подтверждены", summary.confirmed());
        section(text, "в Presto", summary.inPresto());

        text.append("\n<b>Бизнес-ланчи сегодня:</b> ").append(summary.lunchesToday().size()).append('\n');
        int shown = 0;
        for (ButlerPauseSummary.Lunch lunch : summary.lunchesToday()) {
            if (shown++ == LINES) {
                text.append("… и ещё ").append(summary.lunchesToday().size() - LINES).append('\n');
                break;
            }
            text.append("• ").append(lunch.time()).append(" · ").append(escape(lunch.table())).append(" · ")
                    .append(guests(lunch.partySize())).append(" · ").append(escape(lunch.guestName()))
                    .append(" · ").append(lunch.status()).append(" · #").append(lunch.id());
            if (lunch.composition() != null && !lunch.composition().isBlank()) {
                text.append("\n  ").append(escape(lunch.composition()));
            }
            text.append('\n');
        }

        text.append("\n<b>Счета сегодня:</b> ");
        if (summary.bills() == null) {
            text.append("счета выключены (ASTOR_BILLING_ENABLED=false)\n");
        } else {
            ButlerPauseSummary.Bills b = summary.bills();
            text.append("открыто ").append(b.open())
                    .append(" — предварительных ").append(b.estimated())
                    .append(", выставлено ").append(b.issued())
                    .append(", ссылка отправлена ").append(b.linkSent())
                    .append("; оплачено ").append(b.paid()).append('\n');
        }

        text.append("\n<b>Открытые поручения:</b> ");
        if (summary.staffTasks() == null) {
            text.append("недоступны\n");
        } else {
            text.append(summary.staffTasks().size()).append('\n');
            shown = 0;
            for (ButlerPauseSummary.Task task : summary.staffTasks()) {
                if (shown++ == LINES) {
                    text.append("… и ещё ").append(summary.staffTasks().size() - LINES).append('\n');
                    break;
                }
                text.append("• ").append(escape(task.title()));
                if (task.tableCode() != null && !task.tableCode().isBlank()) {
                    text.append(" · стол ").append(escape(task.tableCode()));
                }
                text.append(" · ").append(taskStatus(task.status()));
                if (task.assignee() != null && !task.assignee().isBlank()) {
                    text.append(" · ").append(escape(task.assignee()));
                }
                text.append('\n');
            }
        }
        return text.toString().strip();
    }

    public String summaryText() {
        return format(build());
    }

    private void section(StringBuilder text, String label, List<ButlerPauseSummary.Booking> bookings) {
        text.append("• ").append(label).append(": ").append(bookings.size()).append('\n');
        int shown = 0;
        for (ButlerPauseSummary.Booking booking : bookings) {
            if (shown++ == LINES) {
                text.append("    … и ещё ").append(bookings.size() - LINES).append('\n');
                break;
            }
            text.append("    #").append(booking.id()).append(" · ").append(booking.when()).append(" · ")
                    .append(escape(booking.table())).append(" · ").append(guests(booking.partySize()))
                    .append(" · ").append(escape(booking.guestName()));
            if (booking.lunch()) {
                text.append(" · ланч");
            }
            text.append('\n');
        }
    }

    private ButlerPauseSummary.Bills billsToday(String venue, Instant from, Instant to) {
        if (!billingEnabled) {
            return null;
        }
        int estimated = 0;
        int issued = 0;
        int linkSent = 0;
        int paid = 0;
        try {
            for (GuestBill bill : bills.findOpenedBetween(venue, from, to, LIMIT)) {
                if (bill.status() == GuestBillStatus.PAID) {
                    paid++;
                } else if (bill.status() == GuestBillStatus.ISSUED) {
                    if (bill.paymentLinkSentAt() != null) {
                        linkSent++;
                    } else {
                        issued++;
                    }
                } else if (bill.status() == GuestBillStatus.ESTIMATED) {
                    estimated++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Butler pause summary: bills are not readable: {}", e.toString());
            return null;
        }
        return new ButlerPauseSummary.Bills(estimated, issued, linkSent, paid);
    }

    /** Open tasks of every venue the table knows; the table is read directly so the portal switch does not matter. */
    private List<ButlerPauseSummary.Task> openStaffTasks() {
        try {
            List<String> payloads = jdbcTemplate.queryForList(
                    "SELECT payload FROM astor_staff_tasks ORDER BY changed_at DESC LIMIT " + (LIMIT + 1), String.class);
            List<ButlerPauseSummary.Task> open = new ArrayList<>();
            for (String payload : payloads) {
                StaffTask task;
                try {
                    task = json.readValue(payload, StaffTask.class);
                } catch (Exception e) {
                    log.debug("Butler pause summary: a staff task row is not readable: {}", e.toString());
                    continue;
                }
                if (task.status() == null || task.status().closed()) {
                    continue;
                }
                open.add(new ButlerPauseSummary.Task(task.title(), task.tableCode(), task.status().name(), task.assigneeStaffId()));
            }
            return List.copyOf(open);
        } catch (RuntimeException e) {
            log.warn("Butler pause summary: staff tasks are not readable: {}", e.toString());
            return null;
        }
    }

    private static boolean isLunch(TableReservationOrder order) {
        return BusinessLunchService.SEATING_LABEL.equals(order.seatingPreference());
    }

    /** The hostess comment of a lunch is its composition («Бизнес-ланч: 2 × «Сет 1», 450 ₽. …»); the label is the line's own. */
    private static String composition(TableReservationOrder order) {
        String comment = order.guestComment() == null ? "" : order.guestComment().strip();
        if (comment.startsWith(BusinessLunchService.SEATING_LABEL)) {
            int colon = comment.indexOf(':');
            if (colon > 0 && colon < comment.length() - 1) {
                return comment.substring(colon + 1).strip();
            }
        }
        return comment;
    }

    private static String name(TableReservationOrder order) {
        return order.guestName() == null || order.guestName().isBlank() ? "гость" : order.guestName().strip();
    }

    private static String table(TableReservationOrder order) {
        if (order.tableDisplayName() != null && !order.tableDisplayName().isBlank()) {
            return order.tableDisplayName();
        }
        return order.tableCode() == null || order.tableCode().isBlank() ? "стол не назначен" : order.tableCode();
    }

    private static int party(TableReservationOrder order) {
        return order.partySize() == null ? 0 : order.partySize();
    }

    private static String guests(int party) {
        int mod10 = party % 10;
        int mod100 = party % 100;
        String word = mod10 == 1 && mod100 != 11 ? "гость" : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "гостя" : "гостей";
        return party + " " + word;
    }

    private static String taskStatus(String status) {
        return switch (status == null ? "" : status) {
            case "ASSIGNED" -> "назначено";
            case "ACCEPTED" -> "принято";
            case "IN_PROGRESS" -> "в работе";
            case "HELP_REQUESTED" -> "нужна помощь";
            default -> status == null ? "" : status.toLowerCase();
        };
    }

    private static String lunchStatus(TableReservationStatus status) {
        return switch (status) {
            case AWAITING_MANAGER_CONFIRMATION -> "ждёт хостес";
            case SEATED -> "гость сел";
            default -> "подтверждён";
        };
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
