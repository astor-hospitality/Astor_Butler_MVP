package museon_online.astor_butler.domain.tip;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** After the guest says yes: a card for the waiters in the semi-automatic mode, the recipient's SBP link in the automatic one. */
class TipHandoffServiceTest {

    private static final long CHAT = 1773317437L;
    private static final Instant NOW = Instant.parse("2026-10-06T09:30:00Z");
    private static final TipGuest GUEST = new TipGuest(CHAT, "Наталья Поединенко", "Poedinenko");

    private final TipProperties properties = new TipProperties();
    private final TipService tips = mock(TipService.class);
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final TipStaffNotifier staff = mock(TipStaffNotifier.class);
    private TipHandoffService handoff;

    @BeforeEach
    void setUp() {
        handoff = new TipHandoffService(properties, tips, reservations, staff, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void semiAutomaticSendsTheWaitersACardWithTheGuestsTable() {
        List<TableReservationOrder> active = List.of(
                reservation(78L, "9", NOW.plus(Duration.ofDays(1))),
                reservation(77L, "5", NOW.minus(Duration.ofHours(1))));
        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(active);
        when(staff.tipCard(any(), any(), any())).thenReturn(true);

        TipHandoffService.Handoff result = handoff.afterConfirmed(order(), GUEST);

        assertThat(result.guestText()).isEqualTo("Спасибо! Передал команде — официант подойдёт с QR.");
        assertThat(result.way()).isEqualTo("STAFF_CARD");
        verify(staff).tipCard(order(), GUEST, new TipVisit("5", 77L));
        verify(tips, never()).sbpLinkOf(any());
    }

    @Test
    void aCardWithoutAReservationTodayNamesNoTable() {
        List<TableReservationOrder> tomorrowOnly = List.of(reservation(78L, "9", NOW.plus(Duration.ofDays(1))));
        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(tomorrowOnly);
        when(staff.tipCard(any(), any(), isNull())).thenReturn(true);

        handoff.afterConfirmed(order(), GUEST);

        verify(staff).tipCard(order(), GUEST, null);
    }

    @Test
    void aCardThatCouldNotBeSentAsksTheGuestToCallTheWaiter() {
        when(staff.tipCard(any(), any(), any())).thenReturn(false);

        TipHandoffService.Handoff result = handoff.afterConfirmed(order(), GUEST);

        assertThat(result.guestText()).isEqualTo("Спасибо! Чтобы оставить чаевые, попросите официанта подойти с QR.");
        assertThat(result.way()).isEqualTo("STAFF_CARD_NOT_DELIVERED");
    }

    @Test
    void automaticGivesTheRecipientsSbpLinkAndKeepsIt() {
        properties.setMode(TipMode.SBP_LINK);
        when(tips.sbpLinkOf(order())).thenReturn(Optional.of("https://qr.nspk.ru/team"));

        TipHandoffService.Handoff result = handoff.afterConfirmed(order(), GUEST);

        assertThat(result.guestText()).isEqualTo("Спасибо! Перевести 240 ₽ можно по СБП: https://qr.nspk.ru/team");
        assertThat(result.way()).isEqualTo("SBP_LINK");
        verify(tips).attachSbpUrl(55L, "https://qr.nspk.ru/team");
        verify(staff, never()).tipCard(any(), any(), any());
    }

    @Test
    void automaticWithoutALinkFallsBackToTheCard() {
        properties.setMode(TipMode.SBP_LINK);
        when(tips.sbpLinkOf(order())).thenReturn(Optional.empty());
        when(staff.tipCard(any(), any(), any())).thenReturn(true);

        TipHandoffService.Handoff result = handoff.afterConfirmed(order(), GUEST);

        assertThat(result.way()).isEqualTo("STAFF_CARD");
        verify(tips, never()).attachSbpUrl(anyLong(), anyString());
    }

    @Test
    void theWaiterMarksTheTipReceivedFromTheStaffChatOnly() {
        when(staff.isStaffChat(-100500L)).thenReturn(true);
        when(tips.getOrder(55L)).thenReturn(order(TipOrderStatus.AWAITING_PAYMENT));
        when(tips.markPaid(55L)).thenReturn(order(TipOrderStatus.PAID));

        TipHandoffService.StaffCallbackResult done = handoff.handleStaffCallback("tip_paid:55", -100500L, "Иван");
        TipHandoffService.StaffCallbackResult stranger = handoff.handleStaffCallback("tip_paid:55", CHAT, "Наталья");
        TipHandoffService.StaffCallbackResult other = handoff.handleStaffCallback("table_booking:confirm:1", -100500L, "Иван");

        assertThat(done.handled()).isTrue();
        assertThat(done.answerText()).isEqualTo("Отмечено: чаевые #55 получены");
        assertThat(done.doneLabel()).isEqualTo("✅ Получено — Иван");
        assertThat(stranger.handled()).isTrue();
        assertThat(stranger.doneLabel()).isNull();
        assertThat(other.handled()).isFalse();
        verify(tips).markPaid(55L);
    }

    @Test
    void aTipThatIsNotWaitingIsNotMarked() {
        when(staff.isStaffChat(-100500L)).thenReturn(true);
        when(tips.getOrder(56L)).thenReturn(order(TipOrderStatus.PAID));
        when(tips.getOrder(57L)).thenReturn(order(TipOrderStatus.CANCELLED));
        when(tips.getOrder(58L)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Tip order was not found"));

        assertThat(handoff.handleStaffCallback("tip_paid:56", -100500L, "Иван").answerText()).isEqualTo("Уже отмечено");
        assertThat(handoff.handleStaffCallback("tip_paid:57", -100500L, "Иван").answerText()).isEqualTo("Эти чаевые не ждут оплаты");
        assertThat(handoff.handleStaffCallback("tip_paid:58", -100500L, "Иван").answerText()).isEqualTo("Чаевые не найдены");
        verify(tips, never()).markPaid(anyLong());
    }

    private static TipOrder order() {
        return order(TipOrderStatus.AWAITING_PAYMENT);
    }

    private static TipOrder order(TipOrderStatus status) {
        return new TipOrder(55L, CHAT, CHAT, null, "AERIS", 1L, "Команда AERIS", status, "TELEGRAM",
                24_000L, "RUB", "Наталья Поединенко", "10% от 2400 ₽", null, null,
                Instant.parse("2026-10-06T09:00:00Z"), Instant.parse("2026-10-06T09:00:00Z"));
    }

    private static TableReservationOrder reservation(long id, String table, Instant start) {
        TableReservationOrder order = mock(TableReservationOrder.class);
        when(order.id()).thenReturn(id);
        when(order.status()).thenReturn(TableReservationStatus.CONFIRMED);
        when(order.tableDisplayName()).thenReturn(null);
        when(order.tableCode()).thenReturn(table);
        when(order.requestedStartAt()).thenReturn(start);
        return order;
    }
}
