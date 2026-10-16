package museon_online.astor_butler.domain.entry;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.utils.HostessTopicRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * {@code /ссылка <staff_id> [стол]} ({@code /link}) in the staff, hostess, manager or admin chat (#128), handled the way
 * {@code /stop} is: by chat membership, there is no per-person rights model for chat commands and
 * {@code astor_staff_members} keeps no Telegram id, so the staff member names the id from the directory.
 * The answer is the business lunch deep-link with {@code _h<staff_id>} and a QR rendered here, as a photo with the
 * link in the caption. {@code /ссылка host} is the link for the printed QR at the host stand.
 * A guest (or an unknown group) typing the command is talking to the guest flow, which does not know it.
 */
@Service
@Slf4j
public class StaffHandoffLinkCommandService {

    static final String USAGE = "Персональная ссылка для гостя: /ссылка &lt;staff_id&gt; [стол] — например /ссылка anna 5. "
            + "staff_id — как в справочнике сотрудников; /ссылка host — ссылка для QR на стойке хоста.";

    private final StaffHandoffProperties properties;
    private final GuestEntryService entries;
    private String hostessChatId;
    private String managerChatId;
    private String adminChatId;

    public StaffHandoffLinkCommandService(StaffHandoffProperties properties, GuestEntryService entries,
                                          @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                          @Value("${telegram.booking.manager-chat-id:876857557}") String managerChatId,
                                          @Value("${telegram.admin.chat-id:}") String adminChatId) {
        this.properties = properties;
        this.entries = entries;
        this.hostessChatId = hostessChatId;
        this.managerChatId = managerChatId;
        this.adminChatId = adminChatId;
    }

    /** True when the message was the command from a staff chat and has been answered. */
    public boolean handle(IncomingMessage incoming, AbsSender sender) {
        if (incoming == null || sender == null || !properties.isEnabled()) {
            return false;
        }
        Command command = Command.parse(incoming.text());
        if (command == null) {
            return false;
        }
        if (!isStaffCommandChat(incoming.chatId())) {
            return false;
        }
        String chatId = incoming.chatId().toString();
        if (command.staffId() == null) {
            sendText(sender, chatId, USAGE);
            return true;
        }
        if (!StaffHandoffLink.validId(command.staffId()) || command.tableCode() != null && !StaffHandoffLink.validId(command.tableCode())) {
            sendText(sender, chatId, "staff_id и стол — латиница, цифры и дефис, без пробелов и «_»: /ссылка anna 5");
            return true;
        }
        String parameter = StaffHandoffLink.lunchStartParameter(properties.venueCode(), command.staffId(), command.tableCode());
        if (parameter.length() > StaffHandoffLink.TELEGRAM_PARAMETER_LIMIT) {
            sendText(sender, chatId, "Слишком длинный staff_id или стол: Telegram допускает до " + StaffHandoffLink.TELEGRAM_PARAMETER_LIMIT + " символов в ссылке.");
            return true;
        }
        String url = StaffHandoffLink.url(properties.botUsername(), parameter);
        String caption = caption(command, url);
        if (!sendPhoto(sender, chatId, url, caption)) {
            sendText(sender, chatId, caption);
        }
        log.info("AUDIT Staff handoff link issued: chatId={}, staffId={}, table={}", chatId, command.staffId(), command.tableCode());
        return true;
    }

    /** The caption under the QR: who, which table, the link itself and what the mark means (a journal note, not a bonus). */
    String caption(Command command, String url) {
        String name = entries.displayName(command.staffId());
        StringBuilder text = new StringBuilder("🔗 <b>Ссылка для гостя</b> — ")
                .append(escape(name)).append(" (").append(escape(command.staffId())).append(")");
        if (command.tableCode() != null) {
            text.append(", стол ").append(escape(command.tableCode()));
        }
        text.append("\n").append(url).append("\n\n")
                .append("Покажите QR или перешлите ссылку гостю: он откроет бота и начнёт бизнес-ланч. ")
                .append("В карточке хостес и в system-чате будет «Привёл(а): ").append(escape(name)).append("». ")
                .append("Это отметка в журнале, не бонус и не рейтинг.");
        if (!entries.known(command.staffId())) {
            text.append("\n\n⚠️ В справочнике нет сотрудника «").append(escape(command.staffId()))
                    .append("»: в карточке будет «").append(GuestEntryService.UNKNOWN_NAME).append("». Проверьте staff_id.");
        }
        return text.toString();
    }

    private boolean sendPhoto(AbsSender sender, String chatId, String url, String caption) {
        try {
            byte[] png = QrCodeImages.png(url);
            sender.execute(HostessTopicRouter.route(sender, SendPhoto.builder()
                    .chatId(chatId)
                    .photo(new InputFile(new ByteArrayInputStream(png), "astor-guest-link.png"))
                    .caption(caption)
                    .parseMode("HTML")
                    .build()));
            return true;
        } catch (Exception e) {
            log.warn("Staff handoff QR was not sent: chatId={}, reason={}", chatId, e.toString());
            return false;
        }
    }

    private void sendText(AbsSender sender, String chatId, String text) {
        try {
            sender.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
        } catch (Exception e) {
            log.warn("Staff handoff reply was not sent: chatId={}, reason={}", chatId, e.toString());
        }
    }

    /** Only the configured staff chats: the same chats as for {@code /manual} and {@code /stop}. */
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

    /** {@code /ссылка anna 5}, {@code /ссылка anna стол 5}, {@code /link host}, with or without {@code @bot}; the id as typed. */
    record Command(String staffId, String tableCode) {

        static Command parse(String text) {
            String normalized = text == null ? "" : text.strip();
            if (!normalized.startsWith("/")) {
                return null;
            }
            String[] parts = normalized.split("\\s+");
            String command = parts[0].toLowerCase(Locale.ROOT);
            int mention = command.indexOf('@');
            if (mention > 0) {
                command = command.substring(0, mention);
            }
            if (!"/ссылка".equals(command) && !"/link".equals(command)) {
                return null;
            }
            String staff = parts.length > 1 ? parts[1] : null;
            String table = null;
            for (int i = 2; i < parts.length; i++) {
                if ("стол".equalsIgnoreCase(parts[i]) || "table".equalsIgnoreCase(parts[i])) {
                    continue;
                }
                table = parts[i];
                break;
            }
            return new Command(staff, table);
        }
    }
}
