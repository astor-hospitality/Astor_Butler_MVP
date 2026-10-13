package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.domain.donation.DonationOrder;
import museon_online.astor_butler.domain.donation.DonationOrderCommand;
import museon_online.astor_butler.domain.donation.DonationService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class HiddenHeartScenario implements FsmScenario {

    private static final Pattern MONEY = Pattern.compile(".*\\b\\d{2,7}\\b.*");

    private final FSMStorage fsmStorage;
    private final DonationService donationService;

    /** The guest journey ledger (spec 2026-10-13): optional, silent while the feature is off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents;

    private void journey(IncomingMessage incoming, museon_online.astor_butler.domain.journey.VisitEventKind kind, String note) {
        if (journeyEvents != null && incoming != null) {
            journeyEvents.onChat(incoming.chatId(), kind, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST, note);
        }
    }

    @Override
    public String id() {
        return "HIDDEN_HEART";
    }

    @Override
    public int priority() {
        return 62;
    }

    @Override
    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        return owns(state) || isDonationIntent(normalized);
    }

    @Override
    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        if (state == BotState.DONATION_CONFIRMATION) {
            return confirmDonation(incoming, normalized);
        }
        return collectAmount(incoming, normalized);
    }

    @Override
    public boolean owns(BotState state) {
        BotState canonical = state == null ? BotState.UNKNOWN : state.canonical();
        return canonical == BotState.DONATION_COLLECT_AMOUNT || canonical == BotState.DONATION_CONFIRMATION;
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    private OutgoingMessage collectAmount(IncomingMessage incoming, String text) {
        if (!hasMoney(text)) {
            fsmStorage.setState(incoming.chatId(), BotState.DONATION_COLLECT_AMOUNT);
            return OutgoingMessage.of(
                    incoming,
                    "Hidden Heart включен. Какую сумму хотите направить на благотворительный проект? По умолчанию вклад будет анонимным.",
                    BotState.DONATION_COLLECT_AMOUNT.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("HIDDEN_HEART", "ASK_DONATION_AMOUNT")
            ).withMetadata(Map.of("scenario", id()));
        }

        DonationOrder order = donationService.createDraft(donationOrderCommand(incoming, text));
        journey(incoming, museon_online.astor_butler.domain.journey.VisitEventKind.GOOD_DEED, null);
        fsmStorage.setState(incoming.chatId(), BotState.DONATION_CONFIRMATION);
        return OutgoingMessage.of(
                incoming,
                """
                Собрал donation draft #%s.

                Инициатива: %s
                Сумма: %s ₽
                Приватность: анонимно по умолчанию.

                Подтверждаете?
                """.formatted(order.id(), initiativeTitle(order), rubles(order.amountMinor())),
                BotState.DONATION_CONFIRMATION.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("HIDDEN_HEART", "DONATION_CONFIRMATION", "IMPACT_EVENT_DRAFT")
        ).withMetadata(Map.of(
                "scenario", id(),
                "donationOrderId", order.id(),
                "privacy", "ANONYMOUS_BY_DEFAULT",
                "paymentBoundary", "SBP_FUTURE_INTEGRATION"
        ));
    }

    private OutgoingMessage confirmDonation(IncomingMessage incoming, String text) {
        if (isConfirmIntent(text)) {
            DonationOrder order = donationService.confirmLatestDraft(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    """
                            Зафиксировал Hidden Heart #%s и перевел его в ожидание оплаты.

                            Инициатива: %s
                            Сумма: %s ₽
                            Приватность: %s

                            В impact попадет только агрегированный вклад, без приватных платежных данных. Следующий слой подключит СБП-ссылку инициативы или Telegram Stars invoice.
                            """.formatted(
                            order.id(),
                            initiativeTitle(order),
                            rubles(order.amountMinor()),
                            Boolean.TRUE.equals(order.anonymous()) ? "анонимно" : "с именем гостя"
                    ),
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("HIDDEN_HEART", "DONATION_DRAFT_CONFIRMED", "IMPACT_EVENT_DRAFT", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of(
                    "scenario", id(),
                    "donationOrderId", order.id(),
                    "donationOrderStatus", order.status().name(),
                    "privacy", Boolean.TRUE.equals(order.anonymous()) ? "ANONYMOUS" : "NAMED",
                    "paymentBoundary", "SBP_OR_TELEGRAM_STARS_NEXT"
            ));
        }
        if (isRejectIntent(text)) {
            DonationOrder order = donationService.cancelLatestDraft(incoming.chatId());
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    "Хорошо, отменил donation draft #%s. Возвращаюсь в главное меню.".formatted(order.id()),
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("HIDDEN_HEART", "DONATION_CANCELLED", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of(
                    "scenario", id(),
                    "donationOrderId", order.id(),
                    "donationOrderStatus", order.status().name()
            ));
        }
        fsmStorage.setState(incoming.chatId(), BotState.DONATION_CONFIRMATION);
        return OutgoingMessage.of(
                incoming,
                "Подтвердите, пожалуйста: да — зафиксировать анонимный donation draft, нет — отменить.",
                BotState.DONATION_CONFIRMATION.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("HIDDEN_HEART", "ASK_CONFIRMATION")
        ).withMetadata(Map.of("scenario", id()));
    }

    private boolean isDonationIntent(String text) {
        return containsAny(text, "донат", "благотвор", "поддержать проект", "hidden heart", "пожертв", "помочь проекту", "социальный вклад");
    }

    private boolean hasMoney(String text) {
        return MONEY.matcher(text).matches()
                || text.contains("тысяч")
                || text.contains("тысячи")
                || text.contains("руб");
    }

    private DonationOrderCommand donationOrderCommand(IncomingMessage incoming, String text) {
        return new DonationOrderCommand(
                incoming.chatId(),
                incoming.telegramUserId(),
                null,
                "AERIS",
                null,
                null,
                amountMinor(text),
                "RUB",
                true,
                displayName(incoming),
                text
        );
    }

    private Long amountMinor(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{2,7}").matcher(text);
        if (matcher.find()) {
            return Long.parseLong(matcher.group()) * 100L;
        }
        if (text.contains("тысяч") || text.contains("тысячи")) {
            return 100_000L;
        }
        return null;
    }

    private String rubles(Long amountMinor) {
        if (amountMinor == null) {
            return "уточняется";
        }
        return String.valueOf(amountMinor / 100L);
    }

    private String initiativeTitle(DonationOrder order) {
        if (order.initiativeTitle() == null || order.initiativeTitle().isBlank()) {
            return "Hidden Heart";
        }
        return order.initiativeTitle();
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
