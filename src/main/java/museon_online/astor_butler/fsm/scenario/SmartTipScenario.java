package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.domain.tip.TipOrder;
import museon_online.astor_butler.domain.tip.TipOrderCommand;
import museon_online.astor_butler.domain.tip.TipService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestMoneyText;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SmartTipScenario implements FsmScenario {

    private static final Set<String> LEAVE_WORDS = Set.of(
            "отмена", "отмени", "стоп", "главное меню", "выйти", "назад", "передумал", "передумала", "не надо", "не нужно", "нет");
    private static final String HOW_TO_NAME_THE_SUM = "Напишите ее числом в рублях, например 500. Если передумали, напишите «отмена».";

    private final FSMStorage fsmStorage;
    private final TipService tipService;

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
        String normalized = normalize(text);
        if (state == BotState.TIP_CONFIRMATION) {
            return confirmTip(incoming, normalized);
        }
        return collectAmount(incoming, state, normalized);
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

    private OutgoingMessage collectAmount(IncomingMessage incoming, BotState state, String text) {
        boolean alreadyAsked = state == BotState.TIP_COLLECT_AMOUNT;
        if (alreadyAsked && wantsToLeave(text)) {
            return leave(incoming);
        }
        GuestMoneyText.Reading amount = GuestMoneyText.read(text);
        if (!amount.isAmount()) {
            fsmStorage.setState(incoming.chatId(), BotState.TIP_COLLECT_AMOUNT);
            // The first question is an invitation; a second one has to say what went wrong and how to get out.
            String question = amount.kind() == GuestMoneyText.Kind.NOT_POSITIVE
                    ? "Сумма чаевых должна быть больше нуля. " + HOW_TO_NAME_THE_SUM
                    : alreadyAsked
                    ? "Не смог понять сумму. " + HOW_TO_NAME_THE_SUM
                    : "Красивая благодарность. Какую сумму чаевых хотите оставить?";
            return OutgoingMessage.of(
                    incoming,
                    question,
                    BotState.TIP_COLLECT_AMOUNT.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("SMART_TIP", "ASK_TIP_AMOUNT")
            ).withMetadata(Map.of("scenario", id()));
        }

        TipOrder order = tipService.createDraft(tipOrderCommand(incoming, text, amount.rubles() * 100L));
        fsmStorage.setState(incoming.chatId(), BotState.TIP_CONFIRMATION);
        return OutgoingMessage.of(
                incoming,
                """
                Принял сумму чаевых, благодарность #%s.

                Получатель: %s
                Сумма: %s ₽

                Подтверждаете?
                """.formatted(order.id(), staffName(order), rubles(order.amountMinor())),
                BotState.TIP_CONFIRMATION.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("SMART_TIP", "TIP_CONFIRMATION")
        ).withMetadata(Map.of(
                "scenario", id(),
                "paymentBoundary", "SBP_FUTURE_INTEGRATION",
                "tipOrderId", order.id()
        ));
    }

    private OutgoingMessage confirmTip(IncomingMessage incoming, String text) {
        if (isConfirmIntent(text)) {
            TipOrder order = tipService.confirmLatestDraft(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    """
                            Зафиксировал благодарность #%s и перевел ее в ожидание оплаты.

                            Получатель: %s
                            Сумма: %s ₽

                            Оплата чаевых через бота пока не подключена, поэтому деньги не списаны. Я вернул вас в главное меню.
                            """.formatted(order.id(), staffName(order), rubles(order.amountMinor())),
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
                    "paymentBoundary", "SBP_OR_TELEGRAM_STARS_NEXT"
            ));
        }
        if (isRejectIntent(text)) {
            TipOrder order = tipService.cancelLatestDraft(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    "Хорошо, чаевые #%s отменил. Возвращаюсь в главное меню.".formatted(order.id()),
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
        fsmStorage.setState(incoming.chatId(), BotState.TIP_CONFIRMATION);
        return OutgoingMessage.of(
                incoming,
                "Ответьте, пожалуйста, «да», чтобы записать чаевые, или «нет», чтобы отменить.",
                BotState.TIP_CONFIRMATION.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("SMART_TIP", "ASK_CONFIRMATION")
        ).withMetadata(Map.of("scenario", id()));
    }

    /** The guest changed their mind before naming a sum: nothing was created, so there is nothing to cancel. */
    private OutgoingMessage leave(IncomingMessage incoming) {
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                "Хорошо, чаевые не оформляю. Главное меню оставил под рукой.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("SMART_TIP", "TIP_CANCELLED_BY_GUEST", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of("scenario", id()));
    }

    /** Only a whole reply counts, so "нет, лучше 500" is still read for its sum. */
    private boolean wantsToLeave(String text) {
        String words = text.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
        return LEAVE_WORDS.contains(words);
    }

    private boolean isSmartTipIntent(String text) {
        return containsAny(text, "чаевые", "поблагодарить", "спасибо официанту", "благодарность", "на чай", "накинуть", "тип", "официанту", "бармену");
    }

    private TipOrderCommand tipOrderCommand(IncomingMessage incoming, String text, long amountMinor) {
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
                text
        );
    }

    private String rubles(Long amountMinor) {
        if (amountMinor == null) {
            return "уточняется";
        }
        return String.valueOf(amountMinor / 100L);
    }

    private String staffName(TipOrder order) {
        if (order.staffDisplayName() == null || order.staffDisplayName().isBlank()) {
            return "команда AERIS";
        }
        return order.staffDisplayName();
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
