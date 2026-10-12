package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class HostessReservationApprovalService {

    private static final Pattern CALLBACK = Pattern.compile("^table_booking:(confirm|reject):(\\d+)$");

    private final TableReservationService tableReservationService;
    private final TableReservationNotificationService notificationService;
    private final BookingHostessStateStorage hostessStateStorage;
    private final BookingAutoConfirmPolicy autoConfirmPolicy;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    /** The manager chat gets the cards nobody answered in time and may decide them with the same buttons. */
    @Value("${telegram.booking.manager-chat-id:876857557}")
    private String managerChatId;

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;

    @Value("${astor.booking.confirmation-source:HOSTESS}")
    private BookingConfirmationSource confirmationSource = BookingConfirmationSource.HOSTESS;

    @Transactional
    public boolean handle(IncomingMessage incoming) {
        ManualModeCommand command = ManualModeCommand.parse(incoming == null ? null : incoming.text());
        if (command != null) {
            if (!isStaffCommandChat(incoming.chatId())) {
                // A guest typing /manual is talking to the guest flow, not to this switch.
                return false;
            }
            notificationService.sendStaffNotice(incoming.chatId().toString(), applyManualMode(command, incoming));
            return true;
        }

        if (!isHostessChat(incoming)) {
            return false;
        }

        log.debug("Hostess chat text message consumed by table approval boundary: {}", incoming.text());
        return true;
    }

    @Transactional
    public CallbackResult handleCallback(String callbackData, Long chatId) {
        if (!isHostessChat(chatId) && !isManagerChat(chatId)) {
            return CallbackResult.notHandled();
        }

        java.util.regex.Matcher matcher = CALLBACK.matcher(callbackData == null ? "" : callbackData);
        if (!matcher.matches()) {
            return CallbackResult.notHandled();
        }

        String action = matcher.group(1);
        Long orderId = Long.parseLong(matcher.group(2));
        TableReservationOrder current = tableReservationService.getReservation(orderId);

        if (current.status() == TableReservationStatus.CONFIRMED) {
            return CallbackResult.handled("Бронь #" + orderId + " уже подтверждена");
        }
        if (current.status() == TableReservationStatus.SEATED) {
            return CallbackResult.handled("Бронь #" + orderId + " уже подтверждена, гость за столом");
        }
        if (current.status() == TableReservationStatus.REJECTED) {
            return CallbackResult.handled("Бронь #" + orderId + " уже отменена");
        }
        if (current.status() == TableReservationStatus.EXPIRED) {
            return decideExpired(action, current);
        }

        if ("confirm".equals(action)) {
            if (confirmedByVenueSystem(current)) {
                return CallbackResult.handled("Бронь #" + orderId + " подтверждается в Presto: примите её там, гость получит ответ автоматически");
            }
            TableReservationOrder confirmed = tableReservationService.confirm(orderId);
            notificationService.notifyHostessAcknowledged(confirmed);
            return CallbackResult.handled("Бронь #" + orderId + " подтверждена");
        }

        TableReservationOrder rejected = tableReservationService.reject(orderId);
        notificationService.notifyHostessRejected(rejected);
        return CallbackResult.handled("Бронь #" + orderId + " отменена");
    }

    /**
     * The hold ran out (#87) and the card went to the manager: «Да» takes the table again if it is still free and
     * confirms the booking to the guest, «Нет» is the polite no with alternatives. The table is checked here first,
     * so a taken table is an answer on the button and not an exception inside the transaction.
     */
    private CallbackResult decideExpired(String action, TableReservationOrder current) {
        Long orderId = current.id();
        if (!"confirm".equals(action)) {
            TableReservationOrder rejected = tableReservationService.reject(orderId);
            notificationService.notifyHostessRejected(rejected);
            return CallbackResult.handled("Бронь #" + orderId + " отменена");
        }
        if (!tableReservationService.tableStillFree(current)) {
            return CallbackResult.handled("Бронь #" + orderId + ": стол уже занят на это время, гостю предложено выбрать другое");
        }
        TableReservationOrder confirmed = tableReservationService.confirm(orderId);
        notificationService.notifyHostessAcknowledged(confirmed);
        return CallbackResult.handled("Бронь #" + orderId + " подтверждена, стол снова удержан");
    }

    /** The venue's system holds this booking and is the one to confirm it; Butler only follows. */
    private boolean confirmedByVenueSystem(TableReservationOrder order) {
        return confirmationSource == BookingConfirmationSource.VENUE_SYSTEM
                && order.sbisExternalId() != null
                && !order.sbisExternalId().isBlank();
    }

    /** The "overloaded" switch: on, nothing confirms itself and unanswered cards go to the manager; off, auto-confirm is back. */
    private String applyManualMode(ManualModeCommand command, IncomingMessage incoming) {
        int minutes = autoConfirmPolicy.timeoutMinutes();
        String who = switchedBy(incoming);
        String configNote = autoConfirmPolicy.enabled()
                ? ""
                : "\n\n⚠️ Автоподтверждение сейчас выключено в настройках (ASTOR_BOOKING_AUTO_CONFIRM_ENABLED=false): "
                + "режим сохранён и вступит в силу после включения.";
        return switch (command.action()) {
            case ON -> {
                hostessStateStorage.saveManualMode(new HostessManualMode(true, command.reason(), who, Instant.now()));
                log.info("Booking manual mode switched on: reason={}, by={}", command.reason(), who);
                yield """
                        🔴 <b>Ручной режим включён</b> (%s).
                        Новые заявки ждут вашего «Да» / «Нет»; без ответа через %d мин карточка уходит менеджеру, гостю — «заявка у менеджера».
                        Включил(а): %s.
                        Вернуть автоподтверждение: /manual off""".formatted(escape(command.reason()), minutes, escape(who))
                        + configNote;
            }
            case OFF -> {
                hostessStateStorage.saveManualMode(new HostessManualMode(false, null, who, Instant.now()));
                log.info("Booking manual mode switched off: by={}", who);
                yield """
                        🟢 <b>Ручной режим выключен</b>.
                        Заявки без ответа подтверждаются автоматически через %d мин; «Нет» до этого — отказ, как обычно.
                        Выключил(а): %s.
                        Снова ручной: /manual on""".formatted(minutes, escape(who))
                        + configNote;
            }
            case STATUS -> {
                HostessManualMode mode = hostessStateStorage.manualMode();
                if (mode.manual()) {
                    yield """
                            🔴 Сейчас ручной режим («%s»): без ответа через %d мин заявка уходит менеджеру.
                            Включил(а): %s%s.
                            Выключить: /manual off""".formatted(
                            escape(mode.reason() == null ? ManualModeCommand.DEFAULT_REASON : mode.reason()),
                            minutes,
                            escape(mode.switchedBy() == null ? "неизвестно" : mode.switchedBy()),
                            mode.switchedAt() == null ? "" : " (" + mode.switchedAt() + ")")
                            + configNote;
                }
                yield """
                        🟢 Сейчас автоподтверждение: без ответа через %d мин заявка подтверждается сама.
                        Включить ручной режим: /manual on [причина]""".formatted(minutes)
                        + configNote;
            }
        };
    }

    private static String switchedBy(IncomingMessage incoming) {
        if (incoming.username() != null && !incoming.username().isBlank()) {
            return "@" + incoming.username();
        }
        if (incoming.telegramUserId() != null) {
            return "id " + incoming.telegramUserId();
        }
        return "неизвестно";
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    void setConfirmationSource(BookingConfirmationSource confirmationSource) {
        this.confirmationSource = confirmationSource;
    }

    void setHostessChatId(String hostessChatId) {
        this.hostessChatId = hostessChatId;
    }

    void setManagerChatId(String managerChatId) {
        this.managerChatId = managerChatId;
    }

    void setAdminChatId(String adminChatId) {
        this.adminChatId = adminChatId;
    }

    /** Only the configured staff chats may flip the switch: there is no per-person rights model for chat commands yet. */
    private boolean isStaffCommandChat(Long chatId) {
        return isHostessChat(chatId) || isManagerChat(chatId) || sameChat(adminChatId, chatId);
    }

    private boolean isManagerChat(Long chatId) {
        return sameChat(managerChatId, chatId);
    }

    private static boolean sameChat(String configured, Long chatId) {
        return chatId != null && configured != null && !configured.isBlank() && configured.equals(chatId.toString());
    }

    private boolean isHostessChat(IncomingMessage incoming) {
        if (incoming == null || incoming.chatId() == null || hostessChatId == null || hostessChatId.isBlank()) {
            return false;
        }
        return hostessChatId.equals(incoming.chatId().toString());
    }

    private boolean isHostessChat(Long chatId) {
        if (chatId == null || hostessChatId == null || hostessChatId.isBlank()) {
            return false;
        }
        return hostessChatId.equals(chatId.toString());
    }

    /** {@code /manual on [reason]}, {@code /manual off}, {@code /manual} (status); {@code /ручной} and {@code /авто} as aliases. */
    record ManualModeCommand(Action action, String reason) {

        static final String DEFAULT_REASON = "перегружены";

        enum Action { ON, OFF, STATUS }

        static ManualModeCommand parse(String text) {
            String normalized = text == null ? "" : text.trim();
            if (!normalized.startsWith("/")) {
                return null;
            }
            String[] parts = normalized.split("\\s+", 2);
            String command = parts[0].toLowerCase(Locale.ROOT);
            int mention = command.indexOf('@');
            if (mention > 0) {
                command = command.substring(0, mention);
            }
            String args = parts.length < 2 ? "" : parts[1].trim();
            return switch (command) {
                case "/ручной" -> new ManualModeCommand(Action.ON, reasonOf(args));
                case "/авто" -> new ManualModeCommand(Action.OFF, null);
                case "/manual" -> {
                    String[] argParts = args.split("\\s+", 2);
                    String first = argParts[0].toLowerCase(Locale.ROOT);
                    String rest = argParts.length < 2 ? "" : argParts[1].trim();
                    yield switch (first) {
                        case "on", "вкл", "включить" -> new ManualModeCommand(Action.ON, reasonOf(rest));
                        case "off", "выкл", "выключить", "auto", "авто" -> new ManualModeCommand(Action.OFF, null);
                        default -> new ManualModeCommand(Action.STATUS, null);
                    };
                }
                default -> null;
            };
        }

        private static String reasonOf(String args) {
            return args == null || args.isBlank() ? DEFAULT_REASON : args.trim();
        }
    }

    public record CallbackResult(boolean handled, String answerText) {
        static CallbackResult handled(String answerText) {
            return new CallbackResult(true, answerText);
        }

        static CallbackResult notHandled() {
            return new CallbackResult(false, "");
        }
    }
}
