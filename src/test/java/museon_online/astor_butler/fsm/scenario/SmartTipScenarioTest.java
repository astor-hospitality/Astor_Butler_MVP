package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.billing.BillPayState;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillService;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.tip.TipOrder;
import museon_online.astor_butler.domain.tip.TipOrderCommand;
import museon_online.astor_butler.domain.tip.TipOrderStatus;
import museon_online.astor_butler.domain.tip.TipService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartTipScenarioTest {

    private static final long CHAT = 1773317437L;
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final List<List<String>> PERCENT_ROWS = List.of(List.of("5%", "10%", "15%"), List.of("Свой процент (от 15%)"), List.of("Отмена"));
    private static final List<List<String>> CONFIRM_ROWS = List.of(List.of("Да", "Нет"), List.of("Изменить размер чаевых"));
    private static final List<List<String>> CHECK_ROWS = List.of(List.of("Отмена"));
    private static final String PERCENT_RULE = "Можно выбрать 5%, 10% или 15% кнопкой либо написать свой процент — от 15 до 100. "
            + "Если удобнее сумма в рублях, напишите её с ₽, например 500 ₽.";

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private TipService tipService;

    @Mock
    private GuestBillService bills;

    private final Map<Long, TipDraftStorage.Draft> drafts = new HashMap<>();
    private SmartTipScenario scenario;

    @BeforeEach
    void setUp() {
        scenario = new SmartTipScenario(fsmStorage, tipService, new InMemoryDrafts(), bills,
                new BookingTimeProvider(Clock.fixed(NOW, BookingTimeProvider.VENUE_ZONE)));
    }

    // --- the question: the check first, when Butler does not know it ---

    @Test
    void asksForTheCheckWhenButlerDoesNotKnowIt() {
        IncomingMessage incoming = telegram("хочу оставить чаевые");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "ASK_CHECK_AMOUNT");
        assertThat(outgoing.text()).isEqualTo("Какая сумма в чеке? Напишите её числом, например 2400, — посчитаю чаевые в процентах.");
        assertThat(outgoing.metadata()).containsEntry("replyKeyboardRows", CHECK_ROWS);
        verify(fsmStorage).setState(CHAT, BotState.TIP_COLLECT_AMOUNT);
        verify(tipService, never()).createDraft(any());
    }

    @Test
    void offersPercentsOfTheCheckFromTheGuestsBill() {
        when(bills.billsOf(eq(CHAT), anyInt())).thenReturn(List.of(bill(240_000L, null, NOW.minus(Duration.ofHours(1)), GuestBillStatus.ISSUED)));
        IncomingMessage incoming = telegram("Чаевые");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "ASK_TIP_PERCENT");
        // Right after paying the guest needs no reminder of the check: a short question and the buttons.
        assertThat(outgoing.text()).isEqualTo("Сколько чаевых оставить?");
        assertThat(outgoing.metadata()).containsEntry("replyKeyboardRows", PERCENT_ROWS);
        verify(fsmStorage).setState(CHAT, BotState.TIP_COLLECT_AMOUNT);
    }

    @Test
    void aBillWithoutTheVenuesSumIsCountedFromTheMenu() {
        when(bills.billsOf(eq(CHAT), anyInt())).thenReturn(List.of(bill(null, 111_000L, NOW.plus(Duration.ofMinutes(40)), GuestBillStatus.ESTIMATED)));
        whenDraftsAreCreated();
        scenario.handle(telegram("чаевые"), BotState.READY_FOR_DIALOG, "чаевые");

        OutgoingMessage outgoing = scenario.handle(telegram("15%"), BotState.TIP_COLLECT_AMOUNT, "15%");

        // 15% of 1110 ₽ is 166,5 ₽: a half ruble goes up.
        assertThat(outgoing.text()).contains("Сумма: 167 ₽\n");
    }

    @Test
    void billsOfOtherVisitsAreNotTheCheck() {
        when(bills.billsOf(eq(CHAT), anyInt())).thenReturn(List.of(
                bill(240_000L, null, NOW.plus(Duration.ofHours(5)), GuestBillStatus.ISSUED),
                bill(240_000L, null, NOW.minus(Duration.ofHours(1)), GuestBillStatus.CANCELLED),
                bill(240_000L, null, NOW.minus(Duration.ofHours(30)), GuestBillStatus.PAID),
                bill(240_000L, null, null, GuestBillStatus.ISSUED)));

        OutgoingMessage outgoing = scenario.handle(telegram("чаевые"), BotState.READY_FOR_DIALOG, "чаевые");

        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "ASK_CHECK_AMOUNT");
    }

    // --- choosing a percent ---

    @Test
    void aPercentButtonGivesTheSumInRublesToConfirm() {
        choosingPercentsOf(240_000L);
        whenDraftsAreCreated();
        IncomingMessage incoming = telegram("10%");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_COLLECT_AMOUNT, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_CONFIRMATION.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "TIP_CONFIRMATION");
        assertThat(outgoing.text()).isEqualTo("""
                Принял сумму чаевых, благодарность #55.

                Получатель: Команда AERIS
                Сумма: 240 ₽

                Подтверждаете?""");
        assertThat(outgoing.metadata()).containsEntry("replyKeyboardRows", CONFIRM_ROWS);
        assertThat(outgoing.metadata()).containsEntry("tipPercent", 10);
        assertThat(outgoing.metadata()).containsEntry("tipOrderId", 55L);
        var command = forClass(TipOrderCommand.class);
        verify(tipService).createDraft(command.capture());
        assertThat(command.getValue().amountMinor()).isEqualTo(24_000L);
        assertThat(command.getValue().guestComment()).isEqualTo("10% от 2400 ₽");
        verify(fsmStorage).setState(CHAT, BotState.TIP_CONFIRMATION);
    }

    @Test
    void theTipIsRoundedToWholeRubles() {
        choosingPercentsOf(111_000L);
        whenDraftsAreCreated();

        OutgoingMessage outgoing = scenario.handle(telegram("5%"), BotState.TIP_COLLECT_AMOUNT, "5%");

        assertThat(outgoing.text()).contains("Сумма: 56 ₽\n");
    }

    @Test
    void theGuestCanNameTheirOwnPercentFromFifteen() {
        choosingPercentsOf(240_000L);

        OutgoingMessage prompt = scenario.handle(telegram("Свой процент (от 15%)"), BotState.TIP_COLLECT_AMOUNT, "Свой процент (от 15%)");

        assertThat(prompt.nextState()).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
        assertThat(prompt.actions()).containsExactly("SMART_TIP", "ASK_OWN_PERCENT");
        assertThat(prompt.text()).isEqualTo("Напишите процент числом — от 15 до 100, например 20. "
                + "Если удобнее сумма в рублях, напишите её с ₽, например 500 ₽.");
        assertThat(prompt.metadata()).containsEntry("replyKeyboardRows", PERCENT_ROWS);
        verify(tipService, never()).createDraft(any());

        whenDraftsAreCreated();
        Object[][] cases = {{"20", 48_000L}, {"20%", 48_000L}, {"15", 36_000L}, {"100 %", 240_000L}};
        for (Object[] example : cases) {
            choosingPercentsOf(240_000L);

            OutgoingMessage outgoing = scenario.handle(telegram((String) example[0]), BotState.TIP_COLLECT_AMOUNT, (String) example[0]);

            assertThat(outgoing.nextState()).as((String) example[0]).isEqualTo(BotState.TIP_CONFIRMATION.name());
        }
        var command = forClass(TipOrderCommand.class);
        verify(tipService, times(cases.length)).createDraft(command.capture());
        for (int i = 0; i < cases.length; i++) {
            assertThat(command.getAllValues().get(i).amountMinor()).as((String) cases[i][0]).isEqualTo(cases[i][1]);
        }
    }

    @Test
    void aPercentOutsideTheOfferIsAskedAgainWithTheRule() {
        for (String text : new String[]{"7", "3%", "14%", "150", "500", "17,5%", "ну не знаю"}) {
            choosingPercentsOf(240_000L);

            OutgoingMessage outgoing = scenario.handle(telegram(text), BotState.TIP_COLLECT_AMOUNT, text);

            assertThat(outgoing.nextState()).as(text).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
            assertThat(outgoing.actions()).as(text).containsExactly("SMART_TIP", "TIP_PERCENT_HINT");
            assertThat(outgoing.text()).as(text).isEqualTo(PERCENT_RULE);
            assertThat(outgoing.metadata()).as(text).containsEntry("replyKeyboardRows", PERCENT_ROWS);
        }
        verify(tipService, never()).createDraft(any());
    }

    @Test
    void aSumWithRublesIsStillAccepted() {
        choosingPercentsOf(240_000L);
        whenDraftsAreCreated();

        OutgoingMessage outgoing = scenario.handle(telegram("500 ₽"), BotState.TIP_COLLECT_AMOUNT, "500 ₽");

        assertThat(outgoing.text()).contains("Сумма: 500 ₽\n").doesNotContain("% от");
        assertThat(outgoing.metadata()).doesNotContainKey("tipPercent");
        var command = forClass(TipOrderCommand.class);
        verify(tipService).createDraft(command.capture());
        assertThat(command.getValue().amountMinor()).isEqualTo(50_000L);
        assertThat(command.getValue().guestComment()).isEqualTo("500 ₽");
    }

    @Test
    void aPercentWithItsSignIsNeverReadAsRubles() {
        // In the amount step without a remembered question (the hour has passed) "10%" is still ten percent, not 10 ₽.
        when(bills.billsOf(eq(CHAT), anyInt())).thenReturn(List.of(bill(240_000L, null, NOW.minus(Duration.ofHours(1)), GuestBillStatus.ISSUED)));
        whenDraftsAreCreated();

        OutgoingMessage outgoing = scenario.handle(telegram("10%"), BotState.TIP_COLLECT_AMOUNT, "10%");

        assertThat(outgoing.text()).contains("Сумма: 240 ₽\n");
    }

    // --- the check named by the guest ---

    @Test
    void theGuestTellsTheCheckAndThenChoosesAPercent() {
        scenario.handle(telegram("чаевые"), BotState.READY_FOR_DIALOG, "чаевые");

        OutgoingMessage percents = scenario.handle(telegram("2400"), BotState.TIP_COLLECT_AMOUNT, "2400");

        assertThat(percents.actions()).containsExactly("SMART_TIP", "ASK_TIP_PERCENT");
        assertThat(percents.text()).isEqualTo("Сколько чаевых оставить?");

        whenDraftsAreCreated();
        OutgoingMessage outgoing = scenario.handle(telegram("15%"), BotState.TIP_COLLECT_AMOUNT, "15%");

        assertThat(outgoing.text()).contains("Сумма: 360 ₽\n");
    }

    @Test
    void aPercentNamedBeforeTheCheckIsKept() {
        OutgoingMessage askCheck = scenario.handle(telegram("чаевые 10%"), BotState.READY_FOR_DIALOG, "чаевые 10%");

        assertThat(askCheck.actions()).containsExactly("SMART_TIP", "ASK_CHECK_AMOUNT");
        assertThat(askCheck.text()).isEqualTo("Посчитаю 10% — подскажите сумму в чеке, например 2400.");

        whenDraftsAreCreated();
        OutgoingMessage outgoing = scenario.handle(telegram("2400 рублей"), BotState.TIP_COLLECT_AMOUNT, "2400 рублей");

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_CONFIRMATION.name());
        assertThat(outgoing.text()).contains("Сумма: 240 ₽\n");
    }

    @Test
    void aCheckThatIsNotASumIsAskedAgain() {
        scenario.handle(telegram("чаевые"), BotState.READY_FOR_DIALOG, "чаевые");

        OutgoingMessage outgoing = scenario.handle(telegram("ну не знаю"), BotState.TIP_COLLECT_AMOUNT, "ну не знаю");

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "ASK_CHECK_AMOUNT");
        assertThat(outgoing.text()).isEqualTo("Не смог понять сумму чека. Напишите её числом, например 2400.");
        verify(tipService, never()).createDraft(any());
    }

    // --- confirmation: yes, no, change ---

    @Test
    void movesToConfirmationWhenAmountIsPresent() {
        IncomingMessage incoming = telegram("оставить чаевые 1000 рублей");
        when(tipService.createDraft(any(TipOrderCommand.class))).thenReturn(tipOrder());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_CONFIRMATION.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "TIP_CONFIRMATION");
        assertThat(outgoing.metadata()).containsEntry("paymentBoundary", "SBP_FUTURE_INTEGRATION");
        assertThat(outgoing.metadata()).containsEntry("tipOrderId", 55L);
        assertThat(outgoing.metadata()).containsEntry("replyKeyboardRows", CONFIRM_ROWS);
        assertThat(outgoing.text()).contains("#55", "Команда AERIS", "1000 ₽");
        verify(fsmStorage).setState(incoming.chatId(), BotState.TIP_CONFIRMATION);
    }

    @Test
    void theGuestCanChangeTheTipSizeAndKeepsTheNumber() {
        choosingPercentsOf(240_000L);
        whenDraftsAreCreated();
        scenario.handle(telegram("10%"), BotState.TIP_COLLECT_AMOUNT, "10%");

        OutgoingMessage change = scenario.handle(telegram("Изменить размер чаевых"), BotState.TIP_CONFIRMATION, "Изменить размер чаевых");

        assertThat(change.nextState()).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
        assertThat(change.actions()).containsExactly("SMART_TIP", "TIP_CHANGE_REQUESTED");
        assertThat(change.text()).isEqualTo("Сколько чаевых оставить?");
        assertThat(change.metadata()).containsEntry("replyKeyboardRows", PERCENT_ROWS);
        verify(fsmStorage).setState(CHAT, BotState.TIP_COLLECT_AMOUNT);

        when(tipService.changeDraftAmount(eq(55L), anyLong(), anyString()))
                .thenAnswer(call -> tipOrder(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, call.getArgument(1)));
        OutgoingMessage outgoing = scenario.handle(telegram("15%"), BotState.TIP_COLLECT_AMOUNT, "15%");

        assertThat(outgoing.text()).contains("благодарность #55", "Сумма: 360 ₽\n");
        verify(tipService).changeDraftAmount(55L, 36_000L, "15% от 2400 ₽");
        verify(tipService, times(1)).createDraft(any());
    }

    @Test
    void leavingAfterAChangeCancelsTheTipWaitingForConfirmation() {
        choosingPercentsOf(240_000L);
        whenDraftsAreCreated();
        scenario.handle(telegram("10%"), BotState.TIP_COLLECT_AMOUNT, "10%");
        scenario.handle(telegram("Изменить размер чаевых"), BotState.TIP_CONFIRMATION, "Изменить размер чаевых");

        OutgoingMessage outgoing = scenario.handle(telegram("Отмена"), BotState.TIP_COLLECT_AMOUNT, "Отмена");

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.text()).isEqualTo("Хорошо, чаевые не оформляю. Главное меню оставил под рукой.");
        verify(tipService).cancelDraft(55L);
        assertThat(drafts).doesNotContainKey(CHAT);
    }

    @Test
    void anUnclearAnswerToTheConfirmationOffersAllThreeWays() {
        OutgoingMessage outgoing = scenario.handle(telegram("может быть"), BotState.TIP_CONFIRMATION, "может быть");

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_CONFIRMATION.name());
        assertThat(outgoing.text()).isEqualTo("Ответьте, пожалуйста: «да» — записать чаевые, «нет» — отменить, "
                + "«изменить размер чаевых» — выбрать другую сумму.");
        assertThat(outgoing.metadata()).containsEntry("replyKeyboardRows", CONFIRM_ROWS);
    }

    @Test
    void confirmsTipDraftAndReturnsToMainMenu() {
        drafts.put(CHAT, TipDraftStorage.Draft.empty().withCheck(240_000L, TipDraftStorage.CheckSource.GUEST, null).withOrder(55L));
        IncomingMessage incoming = telegram("да");
        when(tipService.confirmLatestDraft(incoming.chatId())).thenReturn(confirmedTipOrder());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_CONFIRMATION, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "TIP_DRAFT_CONFIRMED", "RETURN_MAIN_MENU");
        assertThat(outgoing.text()).contains("благодарность #55", "ожидание оплаты", "1000 ₽");
        assertThat(outgoing.metadata()).containsEntry("tipOrderId", 55L);
        assertThat(outgoing.metadata()).containsEntry("tipOrderStatus", "AWAITING_PAYMENT");
        verify(tipService).confirmLatestDraft(incoming.chatId());
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        assertThat(drafts).doesNotContainKey(CHAT);
    }

    @Test
    void cancelsTipDraftAndReturnsToMainMenu() {
        drafts.put(CHAT, TipDraftStorage.Draft.empty().withOrder(55L));
        IncomingMessage incoming = telegram("нет");
        when(tipService.cancelLatestDraft(incoming.chatId())).thenReturn(cancelledTipOrder());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_CONFIRMATION, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "TIP_CANCELLED", "RETURN_MAIN_MENU");
        assertThat(outgoing.text()).contains("чаевые #55 отменил");
        assertThat(outgoing.metadata()).containsEntry("tipOrderId", 55L);
        assertThat(outgoing.metadata()).containsEntry("tipOrderStatus", "CANCELLED");
        verify(tipService).cancelLatestDraft(incoming.chatId());
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        assertThat(drafts).doesNotContainKey(CHAT);
    }

    // --- a guest who was answering the old ruble question when the new flow was deployed ---

    @Test
    void theSumIsReadTheWayTheGuestMeantIt() {
        // Before: "пять тысяч" and "полторы тысячи" became 1000, "50 000" became 50, "сто рублей" had no sum at all.
        Object[][] cases = {{"сто рублей", 100L}, {"пять тысяч", 5000L}, {"1 000", 1000L}, {"50 000", 50_000L}, {"полторы тысячи", 1500L}, {"2к", 2000L}};
        when(tipService.createDraft(any(TipOrderCommand.class))).thenReturn(tipOrder());
        for (Object[] example : cases) {
            drafts.clear();
            IncomingMessage incoming = telegram((String) example[0]);

            OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_COLLECT_AMOUNT, incoming.text());

            assertThat(outgoing.nextState()).as((String) example[0]).isEqualTo(BotState.TIP_CONFIRMATION.name());
        }
        var command = forClass(TipOrderCommand.class);
        verify(tipService, times(cases.length)).createDraft(command.capture());
        for (int i = 0; i < cases.length; i++) {
            assertThat(command.getAllValues().get(i).amountMinor()).as((String) cases[i][0]).isEqualTo((Long) cases[i][1] * 100L);
        }
    }

    @Test
    void aMinusOrAZeroIsNotATip() {
        for (String text : new String[]{"-100", "0", "ноль рублей"}) {
            IncomingMessage incoming = telegram(text);

            OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_COLLECT_AMOUNT, incoming.text());

            assertThat(outgoing.nextState()).as(text).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
            assertThat(outgoing.text()).as(text)
                    .isEqualTo("Сумма чаевых должна быть больше нуля. Напишите ее числом в рублях, например 500. Если передумали, напишите «отмена».");
        }
        verify(tipService, never()).createDraft(any());
    }

    @Test
    void anAnswerWithNoSumIsAskedAgainWithAWayOut() {
        for (String text : new String[]{"ну не знаю", "5", "меню кухни"}) {
            IncomingMessage incoming = telegram(text);

            OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_COLLECT_AMOUNT, incoming.text());

            assertThat(outgoing.nextState()).as(text).isEqualTo(BotState.TIP_COLLECT_AMOUNT.name());
            assertThat(outgoing.text()).as(text)
                    .isEqualTo("Не смог понять сумму. Напишите ее числом в рублях, например 500. Если передумали, напишите «отмена».");
        }
        verify(tipService, never()).createDraft(any());
    }

    @Test
    void theGuestCanLeaveAtTheAmountQuestion() {
        for (String text : new String[]{"отмена", "Главное меню", "нет", "Передумал."}) {
            IncomingMessage incoming = telegram(text);

            OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_COLLECT_AMOUNT, incoming.text());

            assertThat(outgoing.nextState()).as(text).isEqualTo(BotState.READY_FOR_DIALOG.name());
            assertThat(outgoing.actions()).as(text).containsExactly("SMART_TIP", "TIP_CANCELLED_BY_GUEST", "RETURN_MAIN_MENU");
            assertThat(outgoing.text()).isEqualTo("Хорошо, чаевые не оформляю. Главное меню оставил под рукой.");
        }
        verify(tipService, never()).createDraft(any());
        verify(tipService, never()).cancelLatestDraft(any());
        verify(tipService, never()).cancelDraft(any());
    }

    @Test
    void aSumThatStartsWithNoIsStillASum() {
        IncomingMessage incoming = telegram("нет, лучше 500");
        when(tipService.createDraft(any(TipOrderCommand.class))).thenReturn(tipOrder());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TIP_COLLECT_AMOUNT, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_CONFIRMATION.name());
        var command = forClass(TipOrderCommand.class);
        verify(tipService).createDraft(command.capture());
        assertThat(command.getValue().amountMinor()).isEqualTo(50_000L);
    }

    @Test
    void theGuestIsNotShownInternalWords() {
        whenDraftsAreCreated();
        when(tipService.confirmLatestDraft(any())).thenReturn(confirmedTipOrder());
        when(tipService.cancelLatestDraft(any())).thenReturn(cancelledTipOrder());
        String everything = String.join("\n",
                scenario.handle(telegram("хочу оставить чаевые"), BotState.READY_FOR_DIALOG, "хочу оставить чаевые").text(),
                scenario.handle(telegram("700"), BotState.TIP_COLLECT_AMOUNT, "700").text(),
                scenario.handle(telegram("Свой процент (от 15%)"), BotState.TIP_COLLECT_AMOUNT, "Свой процент (от 15%)").text(),
                scenario.handle(telegram("7"), BotState.TIP_COLLECT_AMOUNT, "7").text(),
                scenario.handle(telegram("10%"), BotState.TIP_COLLECT_AMOUNT, "10%").text(),
                scenario.handle(telegram("может быть"), BotState.TIP_CONFIRMATION, "может быть").text(),
                scenario.handle(telegram("да"), BotState.TIP_CONFIRMATION, "да").text(),
                scenario.handle(telegram("нет"), BotState.TIP_CONFIRMATION, "нет").text());

        assertThat(everything.toLowerCase()).doesNotContain("draft", "слой", "invoice", "stars", "уточняется", "estimate", "null", "% от");
        assertThat(everything).contains("Оплата чаевых через бота пока не подключена, поэтому деньги не списаны.");
    }

    private void choosingPercentsOf(long checkMinor) {
        drafts.put(CHAT, TipDraftStorage.Draft.empty().withCheck(checkMinor, TipDraftStorage.CheckSource.GUEST, null));
    }

    private void whenDraftsAreCreated() {
        when(tipService.createDraft(any(TipOrderCommand.class)))
                .thenAnswer(call -> tipOrder(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, call.getArgument(0, TipOrderCommand.class).amountMinor()));
    }

    private GuestBill bill(Long venueAmountMinor, Long estimateMinor, Instant visitEndsAt, GuestBillStatus status) {
        return new GuestBill(12L, CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", 77L, "SABY", "order-1",
                status, BillPayState.NOT_REPORTED, estimateMinor, venueAmountMinor, "RUB", "business-lunch:AERIS",
                visitEndsAt, null, null, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(3)));
    }

    private IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(
                CHAT,
                CHAT,
                351,
                284069875,
                text,
                null,
                "Наталья",
                "Поединенко",
                "Poedinenko",
                "ru",
                false,
                "284069875"
        );
    }

    private TipOrder tipOrder() {
        return tipOrder(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, 100_000L);
    }

    private TipOrder confirmedTipOrder() {
        return tipOrder(TipOrderStatus.AWAITING_PAYMENT, 100_000L);
    }

    private TipOrder cancelledTipOrder() {
        return tipOrder(TipOrderStatus.CANCELLED, 100_000L);
    }

    private TipOrder tipOrder(TipOrderStatus status, Long amountMinor) {
        return new TipOrder(
                55L,
                CHAT,
                CHAT,
                null,
                "AERIS",
                1L,
                "Команда AERIS",
                status,
                "TELEGRAM",
                amountMinor,
                "RUB",
                "Наталья Поединенко",
                "оставить чаевые 1000 рублей",
                null,
                null,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }

    private final class InMemoryDrafts extends TipDraftStorage {
        private InMemoryDrafts() {
            super(null, null);
        }

        @Override
        public void save(Long chatId, Draft draft) {
            drafts.put(chatId, draft);
        }

        @Override
        public Optional<Draft> find(Long chatId) {
            return Optional.ofNullable(drafts.get(chatId));
        }

        @Override
        public void clear(Long chatId) {
            drafts.remove(chatId);
        }
    }
}
