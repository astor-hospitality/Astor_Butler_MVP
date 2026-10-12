package museon_online.astor_butler.domain.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.api.glasses.tasks.StaffTask;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the manager gets with /stop: every open status, names and tables, never a phone. */
class ButlerPauseSummaryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Yekaterinburg");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final Instant NOW = TODAY.atTime(10, 0).atZone(ZONE).toInstant();
    private static final Instant FROM = TODAY.atStartOfDay(ZONE).toInstant();
    private static final Instant TOMORROW = TODAY.plusDays(1).atStartOfDay(ZONE).toInstant();
    private static final Instant TO = TODAY.plusDays(ButlerPauseSummaryService.DAYS_AHEAD).atStartOfDay(ZONE).toInstant();
    private static final String PHONE = "+79990001122";

    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final GuestBillRepository bills = mock(GuestBillRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ShiftBriefingProperties properties =
            new ShiftBriefingProperties(false, "AERIS", "Asia/Yekaterinburg", false, false, false, false);
    private final ButlerPauseSummaryService service = new ButlerPauseSummaryService(reservations, bills, jdbc, properties, true);

    @BeforeEach
    void openStatuses() throws Exception {
        service.setClock(Clock.fixed(NOW, ZONE));
        when(reservations.findOrdersStartingBetween(eq("AERIS"), eq(FROM), eq(TO), anyInt())).thenReturn(List.of(
                order(1L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, TODAY, 19, 0, "Иван", "Table 4", 2, null, null, null),
                order(2L, TableReservationStatus.CONFIRMED, TODAY.plusDays(1), 20, 0, "Анна", "Table 7", 4, null, null, null),
                order(3L, TableReservationStatus.CONFIRMED, TODAY, 21, 30, "Олег", null, 3, null, null, "saby-3"),
                order(4L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, TODAY, 13, 0, "Мария", "Table 2", 2, "Бизнес-ланч",
                        "Бизнес-ланч: 2 × «Сет 1», 450 ₽. Суп дня, Салат. В Saby внести вручную.", null),
                order(5L, TableReservationStatus.CANCELLED, TODAY, 18, 0, "Пётр", "Table 1", 2, null, null, null),
                order(6L, TableReservationStatus.CONFIRMED, TODAY.plusDays(1), 13, 30, "Егор", "Table 3", 1, "Бизнес-ланч",
                        "Бизнес-ланч: Борщ × 1. Итого 350 ₽.", "saby-6"),
                order(7L, TableReservationStatus.AWAITING_GUEST_SELECTION, TODAY, 20, 0, "Черновик", null, 2, null, null, null)
        ));
        when(bills.findOpenedBetween(eq("AERIS"), eq(FROM), eq(TOMORROW), anyInt())).thenReturn(List.of(
                bill(1L, GuestBillStatus.ESTIMATED, null),
                bill(2L, GuestBillStatus.ISSUED, null),
                bill(3L, GuestBillStatus.ISSUED, NOW),
                bill(4L, GuestBillStatus.PAID, NOW),
                bill(5L, GuestBillStatus.CANCELLED, null)
        ));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of(
                json.writeValueAsString(task("t1", "Принести плед", "4", StaffTask.Status.IN_PROGRESS, "dasha")),
                json.writeValueAsString(task("t2", "Сервировать", "7", StaffTask.Status.DONE, "vlad")),
                json.writeValueAsString(task("t3", "Проверить VIP", null, StaffTask.Status.ASSIGNED, null)),
                "{not json"
        ));
    }

    @Test
    void sortsOpenBookingsByStatusAndPresto() {
        ButlerPauseSummary summary = service.build();

        assertThat(summary.date()).isEqualTo(TODAY);
        assertThat(summary.awaitingHostess()).extracting(ButlerPauseSummary.Booking::id).containsExactly(1L, 4L);
        assertThat(summary.confirmed()).extracting(ButlerPauseSummary.Booking::id).containsExactly(2L);
        assertThat(summary.inPresto()).extracting(ButlerPauseSummary.Booking::id).containsExactly(3L, 6L);
        assertThat(summary.awaitingHostess().get(0).when()).isEqualTo("12.10 19:00");
        assertThat(summary.awaitingHostess().get(0).table()).isEqualTo("Table 4");
        assertThat(summary.inPresto().get(0).table()).isEqualTo("стол не назначен");
    }

    @Test
    void listsTodaysLunchesWithTheirCompositionOnly() {
        ButlerPauseSummary summary = service.build();

        assertThat(summary.lunchesToday()).hasSize(1);
        ButlerPauseSummary.Lunch lunch = summary.lunchesToday().get(0);
        assertThat(lunch.id()).isEqualTo(4L);
        assertThat(lunch.time()).isEqualTo("13:00");
        assertThat(lunch.status()).isEqualTo("ждёт хостес");
        assertThat(lunch.composition()).isEqualTo("2 × «Сет 1», 450 ₽. Суп дня, Салат. В Saby внести вручную.");
    }

    @Test
    void countsTodaysBillsByStatusAndTheOpenStaffTasks() {
        ButlerPauseSummary summary = service.build();

        assertThat(summary.bills()).isEqualTo(new ButlerPauseSummary.Bills(1, 1, 1, 1));
        assertThat(summary.staffTasks()).extracting(ButlerPauseSummary.Task::title).containsExactly("Принести плед", "Проверить VIP");
        assertThat(summary.staffTasks().get(0)).isEqualTo(new ButlerPauseSummary.Task("Принести плед", "4", "IN_PROGRESS", "dasha"));
    }

    @Test
    void formatsForTheStaffChatWithoutGuestPhones() {
        String text = service.summaryText();

        assertThat(text)
                .startsWith("<b>Открытые статусы на 12.10.2026</b>")
                .contains("• ждут хостес: 2")
                .contains("#1 · 12.10 19:00 · Table 4 · 2 гостя · Иван")
                .contains("#4 · 12.10 13:00 · Table 2 · 2 гостя · Мария · ланч")
                .contains("• подтверждены: 1")
                .contains("#2 · 13.10 20:00 · Table 7 · 4 гостя · Анна")
                .contains("• в Presto: 2")
                .contains("#3 · 12.10 21:30 · стол не назначен · 3 гостя · Олег")
                .contains("<b>Бизнес-ланчи сегодня:</b> 1")
                .contains("• 13:00 · Table 2 · 2 гостя · Мария · ждёт хостес · #4")
                .contains("2 × «Сет 1», 450 ₽. Суп дня, Салат.")
                .contains("<b>Счета сегодня:</b> открыто 3 — предварительных 1, выставлено 1, ссылка отправлена 1; оплачено 1")
                .contains("<b>Открытые поручения:</b> 2")
                .contains("• Принести плед · стол 4 · в работе · dasha")
                .contains("• Проверить VIP · назначено")
                .doesNotContain(PHONE)
                .doesNotContain("Пётр")
                .doesNotContain("Черновик");
    }

    @Test
    void saysWhenBillingIsOffAndWhenTheTaskTableCannotBeRead() {
        ButlerPauseSummaryService billingOff = new ButlerPauseSummaryService(reservations, bills, jdbc, properties, false);
        billingOff.setClock(Clock.fixed(NOW, ZONE));
        when(jdbc.queryForList(anyString(), eq(String.class))).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("q", "sql", null));

        String text = billingOff.summaryText();

        assertThat(text)
                .contains("<b>Счета сегодня:</b> счета выключены (ASTOR_BILLING_ENABLED=false)")
                .contains("<b>Открытые поручения:</b> недоступны");
        verify(bills, org.mockito.Mockito.never()).findOpenedBetween(anyString(), any(), any(), anyInt());
    }

    @Test
    void anEmptyShiftIsStillASummary() {
        when(reservations.findOrdersStartingBetween(anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(bills.findOpenedBetween(anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        assertThat(service.summaryText())
                .contains("• ждут хостес: 0")
                .contains("<b>Бизнес-ланчи сегодня:</b> 0")
                .contains("открыто 0")
                .contains("<b>Открытые поручения:</b> 0");
    }

    private static TableReservationOrder order(Long id, TableReservationStatus status, LocalDate day, int hour, int minute,
                                               String name, String table, int party, String seating, String comment, String externalId) {
        Instant start = day.atTime(hour, minute).atZone(ZONE).toInstant();
        return new TableReservationOrder(id, 1000L + id, 2000L + id, null, table == null ? null : id, table == null ? null : "T" + id, table,
                "MAIN_HALL", seating, status, "TELEGRAM", start, start.plusSeconds(7200), party, name, PHONE, comment,
                876857557L, null, "500", externalId, start.minusSeconds(86400), start.minusSeconds(86400));
    }

    private static GuestBill bill(Long id, GuestBillStatus status, Instant linkSentAt) {
        return new GuestBill(id, 1000L + id, 2000L + id, "AERIS", null, "TELEGRAM", id, "SABY", "ext-" + id, status, null,
                120000L, null, "RUB", "menu", NOW.plusSeconds(3600), linkSentAt, null, NOW.minusSeconds(600), NOW.minusSeconds(600));
    }

    private static StaffTask task(String id, String title, String table, StaffTask.Status status, String assignee) {
        return new StaffTask(id, "AERIS", "glasses", table, title, "", List.of(), StaffTask.Priority.NORMAL, null, assignee,
                status, 1, null, null);
    }
}
