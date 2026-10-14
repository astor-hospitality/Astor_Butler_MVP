package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.config.AstorLinksProperties;
import museon_online.astor_butler.domain.consent.ConsentVaultService;
import museon_online.astor_butler.domain.entry.StaffHandoffLink;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FirstTouchScenario implements FsmScenario {

    private final FSMStorage fsmStorage;
    private final ConsentVaultService consentVaultService;
    private final TableBookingDraftStorage tableBookingDraftStorage;
    private final ChangeCancelDraftStorage changeCancelDraftStorage;
    private final AstorLinksProperties links;

    /** The greeting that names the last lunch (spec 2026-10-13, ASTOR_REPEAT_LUNCH_ENABLED): optional, off by default. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.RepeatLunchGreeting repeatLunchGreeting;

    public void setRepeatLunchGreeting(museon_online.astor_butler.domain.journey.RepeatLunchGreeting repeatLunchGreeting) {
        this.repeatLunchGreeting = repeatLunchGreeting;
    }

    /** {@code astor.staff-handoff.enabled} (#128): with it on, {@code /start h_<staff_id>[_t<стол>]} is a start like any other. */
    @Value("${astor.staff-handoff.enabled:false}")
    private boolean staffHandoffEnabled;

    public String id() {
        return "FIRST_TOUCH";
    }

    public int priority() {
        return 10;
    }

    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        return signalFor(incoming, currentState, text) != null;
    }

    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        FirstTouchSignal signal = signalFor(incoming, currentState, text);
        if (signal == null) {
            throw new IllegalArgumentException("FirstTouchScenario does not support this message");
        }

        return switch (signal) {
            case START_COMMAND -> handleStart(incoming);
            case CONTACT_SHARED -> handleContact(incoming);
            case PRE_AUTH_TEXT -> handleConsentNudge(incoming, currentState, text);
        };
    }

    private FirstTouchSignal signalFor(IncomingMessage incoming, BotState currentState, String text) {
        if (hasContact(incoming)) {
            return FirstTouchSignal.CONTACT_SHARED;
        }
        if ("/start".equalsIgnoreCase(text) || "/restart".equalsIgnoreCase(text)) {
            return FirstTouchSignal.START_COMMAND;
        }
        if (staffHandoffEnabled && StaffHandoffLink.isPlainStart(text)) {
            return FirstTouchSignal.START_COMMAND;
        }
        if (currentState == BotState.UNKNOWN && incoming.hasMessengerUser()) {
            return FirstTouchSignal.START_COMMAND;
        }
        if (currentState != null && currentState.waitsForConsentAndContact()) {
            return FirstTouchSignal.PRE_AUTH_TEXT;
        }
        return null;
    }

    private OutgoingMessage handleStart(IncomingMessage incoming) {
        fsmStorage.clear(incoming.chatId());
        tableBookingDraftStorage.clear(incoming.chatId());
        changeCancelDraftStorage.clear(incoming.chatId());
        if (hasGrantedPrivacyPolicy(incoming)) {
            fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
            return OutgoingMessage.of(
                    incoming,
                    restartText(incoming),
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("SAFE_RESTART", "PREVIEW_REFRESHED", "OPEN_MENU")
            );
        }

        fsmStorage.setState(incoming.chatId(), BotState.CONSENT_REQUIRED);
        String response = "Нажимая кнопку \"Согласиться и поделиться контактом\", вы соглашаетесь с "
                + "<a href=\"" + links.getPolicy() + "\">политикой обработки персональных данных</a>.";

        return OutgoingMessage.of(
                incoming,
                response,
                BotState.CONSENT_REQUIRED.name(),
                true,
                true,
                false,
                false,
                AdminAlert.none(),
                List.of("SAFE_RESTART", "REQUEST_CONTACT", "CONSENT_REQUIRED")
        );
    }

    /** Telegram keeps its consent by Telegram user id; MAX by the internal dialog chat id (no Telegram id exists). */
    private boolean hasGrantedPrivacyPolicy(IncomingMessage incoming) {
        if (incoming.channel() == MessageChannel.MAX) {
            return consentVaultService.hasGrantedMaxPrivacyPolicy(incoming.chatId());
        }
        return consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId());
    }

    private String restartText(IncomingMessage incoming) {
        String text = incoming == null ? "" : incoming.text();
        if ("/restart".equalsIgnoreCase(text == null ? "" : text.trim())) {
            return "Перезапустил диалог и вернул главное меню. Можно начать заново: стол, меню, афиша, мероприятие или помощь команды.";
        }
        if (repeatLunchGreeting != null) {
            java.util.Optional<String> lastLunch = repeatLunchGreeting.text(incoming);
            if (lastLunch.isPresent()) {
                return lastLunch.get();
            }
        }
        // A known guest's /start must carry text: Telegram attaches the guest main menu only to a text message, and the
        // pinned preview card alone left the guest without buttons (live test 13.10).
        return "С возвращением! Чем помочь: стол, бизнес-ланч, меню, афиша или помощь команды. Выберите кнопкой или напишите.";
    }

    private OutgoingMessage handleContact(IncomingMessage incoming) {
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        if (incoming.channel() == MessageChannel.MAX) {
            consentVaultService.grantPrivacyPolicyFromMaxContact(incoming);
        } else {
            consentVaultService.grantPrivacyPolicyFromTelegramContact(incoming);
        }
        return OutgoingMessage.of(
                incoming,
                "Спасибо, контакт получил. Я на связи: можете выбрать действие кнопкой, написать своими словами или отправить голосовое. Я разберу запрос и аккуратно проведу дальше.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("CONTACT_CAPTURED", "OPEN_MENU")
        );
    }

    private OutgoingMessage handleConsentNudge(IncomingMessage incoming, BotState currentState, String text) {
        return OutgoingMessage.of(
                incoming,
                consentNudgeText(incoming),
                BotState.CONSENT_REQUIRED.name(),
                false,
                true,
                false,
                false,
                AdminAlert.none(),
                List.of("PRE_AUTH_CONSENT_NUDGE", "REQUEST_CONTACT", "CONSENT_REQUIRED")
        );
    }

    private String consentNudgeText(IncomingMessage incoming) {
        String[] variants = {
                "Понимаю. До личных деталей я не тороплюсь, но для брони команде AERIS нужен контакт: так хостес сможет подтвердить стол и не потерять вас в вечернем потоке. Нажмите «Согласиться и поделиться контактом» ниже.",
                "Могу помочь и дальше, но сначала нужен маленький формальный шаг: поделиться контактом и согласиться с политикой. После этого приму бронь, голосовое или любой запрос обычным сообщением.",
                "Давайте сделаем надежно: один раз подтвердите контакт кнопкой ниже, и дальше я смогу вести бронь, меню и вопросы без лишней анкеты."
        };
        long seed = incoming == null || incoming.chatId() == null ? 0 : Math.abs(incoming.chatId());
        return variants[(int) (seed % variants.length)];
    }

    private boolean hasContact(IncomingMessage incoming) {
        return incoming.contactPhone() != null && !incoming.contactPhone().isBlank();
    }

    public boolean owns(BotState state) {
        return state != null && state.canonical() == BotState.CONSENT_REQUIRED;
    }

    public boolean sideEffecting() {
        return true;
    }

    void setStaffHandoffEnabled(boolean staffHandoffEnabled) {
        this.staffHandoffEnabled = staffHandoffEnabled;
    }

    private enum FirstTouchSignal {
        START_COMMAND,
        CONTACT_SHARED,
        PRE_AUTH_TEXT
    }
}
