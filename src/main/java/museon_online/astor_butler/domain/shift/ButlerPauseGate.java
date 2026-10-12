package museon_online.astor_butler.domain.shift;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place the FSM asks before it lets a guest scenario write anything (#134). While the shift manager has
 * stopped Butler, a message bound for a booking, a change or cancellation, a business lunch or an event request is
 * answered with «передаю команде», forwarded to the staff chat as a plain card and never reaches the scenario, so
 * nothing is created locally, nothing goes to Saby/Presto and no card with buttons is sent. Menus, the guide, consent,
 * the call for a manager and the rest keep answering as before.
 */
@Component
@Slf4j
public class ButlerPauseGate {

    /** Scenario ids ({@code FsmScenario#id()}) that write: a request, a change, an order, a bill. */
    static final Set<String> WRITING_SCENARIOS = Set.of("TABLE_BOOKING", "CHANGE_CANCEL", "BUSINESS_LUNCH", "EVENT_BOOKING");

    static final String GUEST_TEXT = "Сейчас заявки принимает команда вручную — передаю ваше сообщение, с вами свяжутся.";

    private final ButlerPauseService pauseService;
    private final FSMStorage fsmStorage;
    private final TableReservationNotificationService notificationService;
    private final String hostessChatId;

    public ButlerPauseGate(ButlerPauseService pauseService, FSMStorage fsmStorage,
                           TableReservationNotificationService notificationService,
                           @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId) {
        this.pauseService = pauseService;
        this.fsmStorage = fsmStorage;
        this.notificationService = notificationService;
        this.hostessChatId = hostessChatId;
    }

    public boolean paused() {
        return pauseService.paused();
    }

    /**
     * The guest's answer when the scenario is a writing one and Butler is paused; {@code null} otherwise, and the
     * router goes on to the scenario as usual.
     */
    public OutgoingMessage hold(String scenarioId, IncomingMessage incoming, BotState currentState, String text) {
        if (scenarioId == null || !WRITING_SCENARIOS.contains(scenarioId) || incoming == null || !pauseService.paused()) {
            return null;
        }
        log.info("Butler is paused: {} message from chat {} held for the staff chat", scenarioId, incoming.chatId());
        forwardToStaff(scenarioId, incoming, currentState, text);
        // The guest is left at the main menu: the next message is not taken for a step of the interrupted dialogue.
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                GUEST_TEXT,
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of("BUTLER_PAUSED", "FORWARDED_TO_STAFF")
        ).withMetadata(Map.of("scenario", scenarioId, "butlerPaused", true));
    }

    private void forwardToStaff(String scenarioId, IncomingMessage incoming, BotState currentState, String text) {
        if (hostessChatId == null || hostessChatId.isBlank()) {
            log.warn("Butler is paused but no staff chat is configured (TELEGRAM_HOSTESS_CHAT_ID): guest message from chat {} is not forwarded",
                    incoming.chatId());
            return;
        }
        ButlerPauseState state = pauseService.state();
        String card = """
                ✋ <b>Butler на паузе — сообщение гостя</b>
                <b>Гость:</b> %s · chat %s
                <b>Сценарий:</b> %s%s
                <blockquote>%s</blockquote>
                Ответьте гостю вручную. Остановил(а): %s. Вернуть автоматику: /start_butler""".formatted(
                escape(displayName(incoming)),
                escape(String.valueOf(incoming.chatId())),
                scenarioLabel(scenarioId),
                currentState == null || currentState.canonical() == BotState.READY_FOR_DIALOG ? "" : " (шаг " + currentState.canonical().name() + ")",
                escape(text == null || text.isBlank() ? "(без текста)" : text.strip()),
                escape(state.switchedBy() == null ? "менеджер" : state.switchedBy()));
        notificationService.sendStaffNotice(hostessChatId, card);
    }

    private static String scenarioLabel(String scenarioId) {
        return switch (scenarioId) {
            case "TABLE_BOOKING" -> "бронь стола";
            case "CHANGE_CANCEL" -> "изменение или отмена брони";
            case "BUSINESS_LUNCH" -> "бизнес-ланч";
            case "EVENT_BOOKING" -> "заявка на мероприятие";
            default -> scenarioId;
        };
    }

    /** Name and username only: the guest's phone, if Butler holds it, stays out of the group chat. */
    private static String displayName(IncomingMessage incoming) {
        String first = incoming.firstName() == null ? "" : incoming.firstName().strip();
        String last = incoming.lastName() == null ? "" : incoming.lastName().strip();
        String name = (first + " " + last).strip();
        String username = incoming.username() == null || incoming.username().isBlank() ? "" : "@" + incoming.username().strip();
        if (name.isBlank()) {
            return username.isBlank() ? "гость" : username;
        }
        return username.isBlank() ? name : name + " (" + username + ")";
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
