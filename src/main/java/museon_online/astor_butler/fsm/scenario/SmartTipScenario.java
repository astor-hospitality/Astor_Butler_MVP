package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillService;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.tip.TipGuest;
import museon_online.astor_butler.domain.tip.TipHandoffService;
import museon_online.astor_butler.domain.tip.TipMoney;
import museon_online.astor_butler.domain.tip.TipOrder;
import museon_online.astor_butler.domain.tip.TipOrderCommand;
import museon_online.astor_butler.domain.tip.TipService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestMoneyText;
import museon_online.astor_butler.fsm.understanding.GuestPercentText;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tips right after paying: a button with 5%, 10% or 15% of the check, or a sum the guest writes in rubles; then the
 * sum in rubles to confirm, decline or change. The check is the guest's bill for the visit that is going on or ended
 * within a day; it is needed only for a percent, and when Butler does not know it, the guest who chose a percent is
 * asked for it. "10%" with its sign is a percent; any other number the guest writes is rubles. No money moves here:
 * a confirmed tip waits for the payment boundary.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartTipScenario implements FsmScenario {

    static final String OWN_SUM_BUTTON = "Своя сумма";
    static final String CHANGE_BUTTON = "Изменить размер чаевых";
    static final String CANCEL_BUTTON = "Отмена";
    static final int MAX_PERCENT = 100;

    private static final List<List<String>> PERCENT_ROWS = List.of(List.of("5%", "10%", "15%"), List.of(OWN_SUM_BUTTON), List.of(CANCEL_BUTTON));
    private static final List<List<String>> CONFIRM_ROWS = List.of(List.of("Да", "Нет"), List.of(CHANGE_BUTTON));
    /** A guest who does not remember the check can still write a sum instead. */
    private static final List<List<String>> CHECK_ROWS = List.of(List.of(OWN_SUM_BUTTON), List.of(CANCEL_BUTTON));
    /** The visit a tip is for: one that ended within a day, or is still going on. */
    private static final Duration RECENT_VISIT = Duration.ofHours(24);
    private static final Duration VISIT_GOING_ON = Duration.ofHours(3);
    private static final int BILLS_TO_LOOK_AT = 5;

    private static final Set<String> LEAVE_WORDS = Set.of(
            "отмена", "отмени", "стоп", "главное меню", "выйти", "назад", "передумал", "передумала", "не надо", "не нужно", "нет");
    private static final Set<String> CHANGE_WORDS = Set.of(
            "изменить размер чаевых", "изменить размер", "изменить", "изменить сумму", "другая сумма", "поменять сумму", "другой размер");
    private static final Set<String> OWN_SUM_WORDS = Set.of("своя сумма", "другая сумма", "ввести сумму", "своя");
    /** Asked right after the guest has paid: no reminder of the check and no arithmetic, the buttons say the rest. */
    private static final String PERCENT_QUESTION = "Сколько чаевых оставить?";
    private static final String OWN_SUM_ASK = "Напишите сумму чаевых в рублях, например 300.";
    private static final String CHOICE_HINT = "Выберите 5%, 10% или 15% либо напишите сумму чаевых в рублях, например 300.";
    private static final String DECLINED = "Хорошо, без чаевых. Спасибо, что были у нас!";

    private final FSMStorage fsmStorage;
    private final TipService tipService;
    private final TipDraftStorage drafts;
    private final GuestBillService bills;
    private final BookingTimeProvider timeProvider;
    private final TipHandoffService handoff;

    @Override
    public String id() {
        return "SMART_TIP";
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        return owns(state) || isSmartTipIntent(normalized);
    }

    @Override
    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String raw = text == null ? "" : text.trim();
        String normalized = normalize(raw);
        if (state == BotState.TIP_CONFIRMATION) {
            return confirmTip(incoming, normalized);
        }
        if (state == BotState.TIP_COLLECT_AMOUNT) {
            return answer(incoming, raw, normalized);
        }
        return start(incoming, raw);
    }

    @Override
    public boolean owns(BotState state) {
        BotState canonical = state == null ? BotState.UNKNOWN : state.canonical();
        return canonical == BotState.TIP_COLLECT_AMOUNT || canonical == BotState.TIP_CONFIRMATION;
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    /** A new tip: a percent or a sum named right away is taken, otherwise the question. */
    private OutgoingMessage start(IncomingMessage incoming, String text) {
        drafts.clear(incoming.chatId());
        TipDraftStorage.Draft draft = TipDraftStorage.Draft.empty();
        GuestPercentText.Reading percent = GuestPercentText.read(text);
        if (percent.kind() == GuestPercentText.Kind.MARKED) {
            return choosePercent(incoming, draft, percent.percent());
        }
        if (!text.contains("%")) {
            GuestMoneyText.Reading amount = GuestMoneyText.read(text);
            if (amount.isAmount()) {
                return chooseRubles(incoming, draft, amount.rubles(), text);
            }
        }
        return askPercent(incoming, draft, List.of("SMART_TIP", "ASK_TIP_PERCENT"));
    }

    /**
     * An answer to the tip question: a percent button, the own-sum button, a sum in rubles, or the check when the guest
     * chose a percent and Butler did not know the check. Without a remembered question (the hour has passed) the
     * answer is read the same way.
     */
    private OutgoingMessage answer(IncomingMessage incoming, String text, String normalized) {
        if (wantsToLeave(normalized)) {
            return leave(incoming);
        }
        TipDraftStorage.Draft draft = drafts.find(incoming.chatId()).orElse(TipDraftStorage.Draft.empty());
        if (isOwnSumButton(normalized)) {
            return askOwnSum(incoming, draft);
        }
        GuestPercentText.Reading percent = GuestPercentText.read(text);
        if (draft.awaitingCheck()) {
            return answerCheck(incoming, draft, text, percent);
        }
        if (percent.kind() == GuestPercentText.Kind.MARKED) {
            return choosePercent(incoming, draft, percent.percent());
        }
        if (!text.contains("%")) {
            GuestMoneyText.Reading amount = GuestMoneyText.read(text);
            if (amount.isAmount()) {
                return chooseRubles(incoming, draft, amount.rubles(), text);
            }
        }
        return choiceHint(incoming, draft);
    }

    private OutgoingMessage answerCheck(IncomingMessage incoming, TipDraftStorage.Draft draft, String text, GuestPercentText.Reading percent) {
        if (percent.kind() == GuestPercentText.Kind.MARKED) {
            // The guest chose the share first; it is kept until the check is known.
            return askCheck(incoming, draft.askingForCheck(allowed(percent.percent()) ? percent.percent() : draft.pendingPercent()), false);
        }
        GuestMoneyText.Reading amount = GuestMoneyText.read(text);
        if (text.contains("%") || !amount.isAmount()) {
            return askCheck(incoming, draft, true);
        }
        TipDraftStorage.Draft withCheck = draft.withCheck(amount.rubles() * 100L, TipDraftStorage.CheckSource.GUEST, null);
        if (draft.pendingPercent() != null) {
            return choosePercent(incoming, withCheck.choosing(), draft.pendingPercent());
        }
        return askPercent(incoming, withCheck, List.of("SMART_TIP", "ASK_TIP_PERCENT"));
    }

    private OutgoingMessage choosePercent(IncomingMessage incoming, TipDraftStorage.Draft draft, int percent) {
        if (!allowed(percent)) {
            return choiceHint(incoming, draft);
        }
        TipDraftStorage.Draft known = withKnownCheck(draft, incoming.chatId());
        if (!known.knowsCheck()) {
            return askCheck(incoming, known.askingForCheck(percent), false);
        }
        long rubles = share(known.checkMinor(), percent);
        String note = "%d%% от %s ₽".formatted(percent, money(known.checkMinor()));
        return confirm(incoming, known.choosing(), rubles * 100L, note, percent);
    }

    private OutgoingMessage chooseRubles(IncomingMessage incoming, TipDraftStorage.Draft draft, long rubles, String text) {
        return confirm(incoming, draft.choosing(), rubles * 100L, text, null);
    }

    /**
     * The sum in rubles and nothing about how it was counted: the guest chose the share a moment ago.
     * How it was counted stays in the tip's comment, for the team.
     *
     * @param percent the share the sum was counted from; null for a sum named in rubles
     */
    private OutgoingMessage confirm(IncomingMessage incoming, TipDraftStorage.Draft draft, long amountMinor, String comment, Integer percent) {
        TipOrder order = draft.tipOrderId() == null
                ? tipService.createDraft(tipOrderCommand(incoming, comment, amountMinor))
                : changeOrCreate(incoming, draft.tipOrderId(), amountMinor, comment);
        drafts.save(incoming.chatId(), draft.withOrder(order.id()));
        fsmStorage.setState(incoming.chatId(), BotState.TIP_CONFIRMATION);
        String text = """
                Принял сумму чаевых, благодарность #%s.

                Получатель: %s
                Сумма: %s ₽

                Подтверждаете?""".formatted(order.id(), staffName(order), rubles(order.amountMinor()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenario", id());
        metadata.put("paymentBoundary", "SBP_FUTURE_INTEGRATION");
        metadata.put("tipOrderId", order.id());
        metadata.put("replyKeyboardRows", CONFIRM_ROWS);
        if (percent != null) {
            metadata.put("tipPercent", percent);
        }
        return OutgoingMessage.of(
                incoming,
                text,
                BotState.TIP_CONFIRMATION.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("SMART_TIP", "TIP_CONFIRMATION")
        ).withMetadata(metadata);
    }

    private TipOrder changeOrCreate(IncomingMessage incoming, Long tipOrderId, long amountMinor, String comment) {
        try {
            return tipService.changeDraftAmount(tipOrderId, amountMinor, comment);
        } catch (RuntimeException e) {
            // The tip is no longer waiting (confirmed or cancelled elsewhere): the new size becomes a new thanks.
            log.info("Tip #{} could not be changed, writing a new one: {}", tipOrderId, e.getMessage());
            return tipService.createDraft(tipOrderCommand(incoming, comment, amountMinor));
        }
    }

    /** The check is not needed to ask: only a percent the guest picks is counted from it. */
    private OutgoingMessage askPercent(IncomingMessage incoming, TipDraftStorage.Draft draft, List<String> actions) {
        return ask(incoming, draft, PERCENT_QUESTION, actions);
    }

    private OutgoingMessage askOwnSum(IncomingMessage incoming, TipDraftStorage.Draft draft) {
        return ask(incoming, draft, OWN_SUM_ASK, List.of("SMART_TIP", "ASK_OWN_SUM"));
    }

    private OutgoingMessage choiceHint(IncomingMessage incoming, TipDraftStorage.Draft draft) {
        return ask(incoming, draft, CHOICE_HINT, List.of("SMART_TIP", "TIP_CHOICE_HINT"));
    }

    private OutgoingMessage ask(IncomingMessage incoming, TipDraftStorage.Draft draft, String text, List<String> actions) {
        drafts.save(incoming.chatId(), draft.choosing());
        fsmStorage.setState(incoming.chatId(), BotState.TIP_COLLECT_AMOUNT);
        return reply(incoming, text, BotState.TIP_COLLECT_AMOUNT, actions, PERCENT_ROWS);
    }

    /** @param draft already waiting for the check, with the percent the guest chose */
    private OutgoingMessage askCheck(IncomingMessage incoming, TipDraftStorage.Draft draft, boolean again) {
        drafts.save(incoming.chatId(), draft);
        fsmStorage.setState(incoming.chatId(), BotState.TIP_COLLECT_AMOUNT);
        String question = again
                ? "Не смог понять сумму чека. Напишите её числом, например 2400."
                : draft.pendingPercent() != null
                ? "Посчитаю %d%% — подскажите сумму в чеке, например 2400.".formatted(draft.pendingPercent())
                : "Подскажите сумму в чеке, например 2400.";
        return reply(incoming, question, BotState.TIP_COLLECT_AMOUNT, List.of("SMART_TIP", "ASK_CHECK_AMOUNT"), CHECK_ROWS);
    }

    /**
     * The check of the visit the tip is for: the guest's bill whose visit ended within a day or is going on,
     * the venue's sum when it is known, Butler's count by the menu otherwise.
     */
    private TipDraftStorage.Draft withKnownCheck(TipDraftStorage.Draft draft, Long chatId) {
        if (draft.knowsCheck()) {
            return draft;
        }
        Instant now = timeProvider.now();
        try {
            for (GuestBill bill : bills.billsOf(chatId, BILLS_TO_LOOK_AT)) {
                if (!isForTheVisitAround(bill, now)) {
                    continue;
                }
                if (positive(bill.venueAmountMinor())) {
                    return draft.withCheck(bill.venueAmountMinor(), TipDraftStorage.CheckSource.VENUE, bill.id());
                }
                if (positive(bill.estimateMinor())) {
                    return draft.withCheck(bill.estimateMinor(), TipDraftStorage.CheckSource.ESTIMATE, bill.id());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Tip check could not be read from the guest's bills: chatId={}, reason={}", chatId, e.toString());
        }
        return draft;
    }

    private static boolean isForTheVisitAround(GuestBill bill, Instant now) {
        Instant end = bill.visitEndsAt();
        return bill.status() != GuestBillStatus.CANCELLED
                && end != null
                && !end.isBefore(now.minus(RECENT_VISIT))
                && !end.isAfter(now.plus(VISIT_GOING_ON));
    }

    private OutgoingMessage confirmTip(IncomingMessage incoming, String text) {
        if (isConfirmIntent(text)) {
            TipOrder order = tipService.confirmLatestDraft(incoming.chatId());
            drafts.clear(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            // Butler takes no money: the waiters come with a QR, or the guest gets the recipient's SBP link.
            TipHandoffService.Handoff next = handoff.afterConfirmed(order, tipGuest(incoming));
            return OutgoingMessage.of(
                    incoming,
                    next.guestText(),
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("SMART_TIP", "TIP_DRAFT_CONFIRMED", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of(
                    "scenario", id(),
                    "tipOrderId", order.id(),
                    "tipOrderStatus", order.status().name(),
                    "tipHandoff", next.way()
            ));
        }
        if (isRejectIntent(text)) {
            TipOrder order = tipService.cancelLatestDraft(incoming.chatId());
            drafts.clear(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    DECLINED,
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("SMART_TIP", "TIP_CANCELLED", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of(
                    "scenario", id(),
                    "tipOrderId", order.id(),
                    "tipOrderStatus", order.status().name()
            ));
        }
        if (isChangeIntent(text)) {
            return changeSize(incoming);
        }
        fsmStorage.setState(incoming.chatId(), BotState.TIP_CONFIRMATION);
        return reply(
                incoming,
                "Ответьте, пожалуйста: «да» — записать чаевые, «нет» — отменить, «изменить размер чаевых» — выбрать другую сумму.",
                BotState.TIP_CONFIRMATION,
                List.of("SMART_TIP", "ASK_CONFIRMATION"),
                CONFIRM_ROWS
        );
    }

    /** Back to the percent question; the tip waiting for confirmation keeps its number and gets the new sum. */
    private OutgoingMessage changeSize(IncomingMessage incoming) {
        TipDraftStorage.Draft draft = drafts.find(incoming.chatId()).orElse(TipDraftStorage.Draft.empty());
        if (draft.tipOrderId() == null) {
            // The remembered tip is gone (the hour has passed): the waiting one is dropped, the new size is a new thanks.
            try {
                tipService.cancelLatestDraft(incoming.chatId());
            } catch (RuntimeException e) {
                log.info("No tip waiting for confirmation to drop: chatId={}, reason={}", incoming.chatId(), e.getMessage());
            }
        }
        return askPercent(incoming, draft, List.of("SMART_TIP", "TIP_CHANGE_REQUESTED"));
    }

    /** The guest changed their mind before confirming; a tip they were resizing is dropped too. */
    private OutgoingMessage leave(IncomingMessage incoming) {
        drafts.find(incoming.chatId())
                .map(TipDraftStorage.Draft::tipOrderId)
                .ifPresent(orderId -> {
                    try {
                        tipService.cancelDraft(orderId);
                    } catch (RuntimeException e) {
                        log.info("Tip #{} was not waiting any more: {}", orderId, e.getMessage());
                    }
                });
        drafts.clear(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return reply(
                incoming,
                "Хорошо, чаевые не оформляю. Главное меню оставил под рукой.",
                BotState.READY_FOR_DIALOG,
                List.of("SMART_TIP", "TIP_CANCELLED_BY_GUEST", "RETURN_MAIN_MENU"),
                null
        );
    }

    private OutgoingMessage reply(IncomingMessage incoming, String text, BotState next, List<String> actions, List<List<String>> rows) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenario", id());
        if (rows != null) {
            metadata.put("replyKeyboardRows", rows);
        }
        return OutgoingMessage.of(
                incoming,
                text,
                next.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                actions
        ).withMetadata(metadata);
    }

    /** The buttons give 5, 10 and 15; a percent typed with its sign is taken too, as long as it is a share of the check. */
    static boolean allowed(int percent) {
        return percent >= 1 && percent <= MAX_PERCENT;
    }

    /** The share of the check in whole rubles; half a ruble goes up, and a tip is never less than a ruble. */
    static long share(long checkMinor, int percent) {
        return Math.max(1L, (checkMinor * percent + 5_000L) / 10_000L);
    }

    static String money(long minor) {
        return TipMoney.rubles(minor);
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    /** Only a whole reply counts, so "нет, лучше 500" is still read for its sum. */
    private boolean wantsToLeave(String text) {
        return LEAVE_WORDS.contains(words(text));
    }

    private boolean isChangeIntent(String text) {
        return CHANGE_WORDS.contains(words(text));
    }

    private boolean isOwnSumButton(String text) {
        return OWN_SUM_WORDS.contains(words(text));
    }

    private static String words(String text) {
        return text.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean isSmartTipIntent(String text) {
        return containsAny(text, "чаевые", "поблагодарить", "спасибо официанту", "благодарность", "на чай", "накинуть", "тип", "официанту", "бармену");
    }

    private TipOrderCommand tipOrderCommand(IncomingMessage incoming, String comment, long amountMinor) {
        return new TipOrderCommand(
                incoming.chatId(),
                incoming.telegramUserId(),
                null,
                "AERIS",
                null,
                null,
                amountMinor,
                "RUB",
                displayName(incoming),
                comment
        );
    }

    private String rubles(Long amountMinor) {
        if (amountMinor == null) {
            return "уточняется";
        }
        return money(amountMinor);
    }

    private String staffName(TipOrder order) {
        if (order.staffDisplayName() == null || order.staffDisplayName().isBlank()) {
            return "Команда AERIS";
        }
        return order.staffDisplayName();
    }

    /** The waiters' card shows the name and the username apart, so a guest without a name is not "@x (@x)". */
    private TipGuest tipGuest(IncomingMessage incoming) {
        String firstName = incoming.firstName() == null ? "" : incoming.firstName().trim();
        String lastName = incoming.lastName() == null ? "" : incoming.lastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        String username = incoming.username() == null ? "" : incoming.username().trim();
        return new TipGuest(incoming.chatId(), fullName.isEmpty() ? null : fullName, username.isEmpty() ? null : username);
    }

    private String displayName(IncomingMessage incoming) {
        String firstName = incoming.firstName() == null ? "" : incoming.firstName().trim();
        String lastName = incoming.lastName() == null ? "" : incoming.lastName().trim();
        String username = incoming.username() == null ? "" : incoming.username().trim();
        String fullName = (firstName + " " + lastName).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (!username.isBlank()) {
            return "@" + username;
        }
        return "unknown";
    }

    private boolean isConfirmIntent(String text) {
        return text.equals("да")
                || text.equals("ок")
                || text.equals("okay")
                || text.equals("ok")
                || text.equals("подтверждаю")
                || text.equals("подтвердить")
                || text.equals("согласен")
                || text.equals("согласна");
    }

    private boolean isRejectIntent(String text) {
        return text.equals("нет")
                || text.equals("не надо")
                || text.equals("отмена")
                || text.equals("отмени")
                || text.equals("передумал")
                || text.equals("передумала")
                || text.equals("главное меню")
                || text.equals("не подтверждаю");
    }

    private boolean containsAny(String text, String... variants) {
        for (String variant : variants) {
            if (text.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
