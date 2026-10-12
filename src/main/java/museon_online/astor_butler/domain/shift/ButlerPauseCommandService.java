package museon_online.astor_butler.domain.shift;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The shift manager's commands (#134), handled the way {@code /manual} is: in the staff (hostess), manager or admin
 * chat only, by chat membership — there is no per-person rights model for chat commands. A guest typing them is
 * talking to the guest flow, which does not know them.
 *
 * <ul>
 *   <li>{@code /stop} ({@code /стоп}) — pauses Butler and posts the open statuses to the chat and to the staff chat;</li>
 *   <li>{@code /start_butler} ({@code /go}) — Butler works again;</li>
 *   <li>{@code /status_butler} — paused or running, with the same summary.</li>
 * </ul>
 * Both switches are idempotent and leave a line in the log and the system chat («Butler остановлен менеджером …»).
 * The guest's {@code /start} is a different command and stays with the guest.
 */
@Service
@Slf4j
public class ButlerPauseCommandService {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Yekaterinburg"));

    private final ButlerPauseService pauseService;
    private final ButlerPauseSummaryService summaryService;
    private final TableReservationNotificationService notificationService;
    private final TelegramSystemNotifier systemNotifier;

    private String hostessChatId;
    private String managerChatId;
    private String adminChatId;

    public ButlerPauseCommandService(ButlerPauseService pauseService, ButlerPauseSummaryService summaryService,
                                     TableReservationNotificationService notificationService,
                                     TelegramSystemNotifier systemNotifier,
                                     @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                     @Value("${telegram.booking.manager-chat-id:876857557}") String managerChatId,
                                     @Value("${telegram.admin.chat-id:}") String adminChatId) {
        this.pauseService = pauseService;
        this.summaryService = summaryService;
        this.notificationService = notificationService;
        this.systemNotifier = systemNotifier;
        this.hostessChatId = hostessChatId;
        this.managerChatId = managerChatId;
        this.adminChatId = adminChatId;
    }

    /** True when the message was one of the commands from a staff chat and has been answered. */
    public boolean handle(IncomingMessage incoming) {
        if (incoming == null || !pauseService.enabled()) {
            return false;
        }
        Command command = Command.parse(incoming.text());
        if (command == null) {
            return false;
        }
        if (!isStaffCommandChat(incoming.chatId())) {
            // A guest (or an unknown group) typing /stop is not the shift manager; the guest flow answers as usual.
            return false;
        }
        String chatId = incoming.chatId().toString();
        String who = who(incoming);
        switch (command) {
            case STOP -> {
                boolean changed = pauseService.stop(who);
                ButlerPauseState state = pauseService.state();
                String head = changed
                        ? "⏸ <b>Butler остановлен.</b> Остановил(а): %s.".formatted(escape(who))
                        : "⏸ Butler уже остановлен%s.".formatted(switchedLine(state));
                String body = head + "\n" + PAUSED_NOTE + "\n\n" + summary();
                notificationService.sendStaffNotice(chatId, body);
                if (changed) {
                    tellStaffChat(chatId, body);
                    audit("Butler остановлен менеджером " + who);
                }
            }
            case START -> {
                boolean changed = pauseService.resume(who);
                String text = changed
                        ? "▶️ <b>Butler снова работает.</b> Возобновил(а): %s.\nЗаявки, ланчи и таймеры снова в автоматическом режиме.".formatted(escape(who))
                        : "▶️ Butler и так работает. Остановить: /stop";
                notificationService.sendStaffNotice(chatId, text);
                if (changed) {
                    tellStaffChat(chatId, text);
                    audit("Butler снова работает: включил(а) менеджер " + who);
                }
            }
            case STATUS -> {
                ButlerPauseState state = pauseService.state();
                String head = state.paused()
                        ? "⏸ <b>Butler остановлен</b>%s.\n%s\nВернуть: /start_butler".formatted(switchedLine(state), PAUSED_NOTE)
                        : "▶️ <b>Butler работает.</b> Остановить и вести смену вручную: /stop";
                notificationService.sendStaffNotice(chatId, head + "\n\n" + summary());
            }
        }
        return true;
    }

    static final String PAUSED_NOTE = "Новые заявки, изменения, ланчи и оплата идут к команде вручную; гостю — «передаю ваше сообщение, с вами свяжутся». "
            + "Меню, видео-тур и ответы на вопросы работают. Таймеры (автоподтверждение, утренняя проверка, опрос Presto, ссылки на оплату) стоят.";

    private String summary() {
        try {
            return summaryService.summaryText();
        } catch (RuntimeException e) {
            log.warn("Butler pause summary failed: {}", e.toString());
            return "Сводку открытых статусов собрать не удалось (" + escape(e.getClass().getSimpleName()) + "): проверьте карточки в staff-чате.";
        }
    }

    /** The staff chat is where the shift reads; a command from the manager or admin chat is echoed there. */
    private void tellStaffChat(String commandChatId, String text) {
        if (hostessChatId == null || hostessChatId.isBlank() || hostessChatId.equals(commandChatId)) {
            return;
        }
        notificationService.sendStaffNotice(hostessChatId, text);
    }

    private void audit(String line) {
        log.info("AUDIT {}", line);
        try {
            systemNotifier.sendSystemNotice(line);
        } catch (RuntimeException e) {
            log.debug("Butler pause audit line was not sent to the system chat: {}", e.toString());
        }
    }

    private static String switchedLine(ButlerPauseState state) {
        if (state.switchedBy() == null && state.switchedAt() == null) {
            return "";
        }
        return " (" + escape(state.switchedBy() == null ? "неизвестно" : state.switchedBy())
                + (state.switchedAt() == null ? "" : ", " + AT.format(state.switchedAt())) + ")";
    }

    /** The manager's name as the audit line says it: first and last name, else @username, else the Telegram id. */
    private static String who(IncomingMessage incoming) {
        String first = incoming.firstName() == null ? "" : incoming.firstName().strip();
        String last = incoming.lastName() == null ? "" : incoming.lastName().strip();
        String name = (first + " " + last).strip();
        String username = incoming.username() == null || incoming.username().isBlank() ? "" : "@" + incoming.username().strip();
        if (!name.isBlank()) {
            return username.isBlank() ? name : name + " (" + username + ")";
        }
        if (!username.isBlank()) {
            return username;
        }
        return incoming.telegramUserId() == null ? "неизвестно" : "id " + incoming.telegramUserId();
    }

    /** Only the configured staff chats may flip the switch: the same chats as for {@code /manual}. */
    private boolean isStaffCommandChat(Long chatId) {
        return sameChat(hostessChatId, chatId) || sameChat(managerChatId, chatId) || sameChat(adminChatId, chatId);
    }

    private static boolean sameChat(String configured, Long chatId) {
        return chatId != null && configured != null && !configured.isBlank() && configured.equals(chatId.toString());
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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

    /** {@code /stop}, {@code /стоп}; {@code /start_butler}, {@code /go}; {@code /status_butler} — with or without {@code @bot}. */
    enum Command {
        STOP, START, STATUS;

        static Command parse(String text) {
            String normalized = text == null ? "" : text.strip();
            if (!normalized.startsWith("/")) {
                return null;
            }
            String command = normalized.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            int mention = command.indexOf('@');
            if (mention > 0) {
                command = command.substring(0, mention);
            }
            return switch (command) {
                case "/stop", "/стоп" -> STOP;
                case "/start_butler", "/go" -> START;
                case "/status_butler" -> STATUS;
                default -> null;
            };
        }
    }
}
