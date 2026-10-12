package museon_online.astor_butler.domain.lunch.scoreboard;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.shift.ShiftBriefingProperties;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;

/**
 * {@code /табло} ({@code /scoreboard}) in the staff, manager or admin chat (#175): the same table the clock sends at
 * {@code send-at}, now. {@code /табло вчера} is yesterday's — reviews that came in after the evening send are in it.
 * Gated the way {@code /stop} is (#134): by chat membership only; from a guest chat the command is unknown and the
 * guest flow answers as usual. With the feature off the command is unknown everywhere.
 */
@Service
@Slf4j
public class LunchScoreboardCommandService {

    private final LunchScoreboardProperties properties;
    private final LunchScoreboardService service;
    private final LunchScoreboardFormatter formatter;
    private final TableReservationNotificationService notifications;
    private final ShiftBriefingProperties venue;
    private final String hostessChatId;
    private final String managerChatId;
    private final String adminChatId;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public LunchScoreboardCommandService(LunchScoreboardProperties properties, LunchScoreboardService service,
                                         LunchScoreboardFormatter formatter,
                                         TableReservationNotificationService notifications,
                                         ShiftBriefingProperties venue,
                                         @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                         @Value("${telegram.booking.manager-chat-id:876857557}") String managerChatId,
                                         @Value("${telegram.admin.chat-id:}") String adminChatId) {
        this.properties = properties;
        this.service = service;
        this.formatter = formatter;
        this.notifications = notifications;
        this.venue = venue;
        this.hostessChatId = hostessChatId;
        this.managerChatId = managerChatId;
        this.adminChatId = adminChatId;
    }

    /** True when the message was the command from a staff chat and has been answered. */
    public boolean handle(IncomingMessage incoming) {
        if (incoming == null || !properties.isEnabled()) {
            return false;
        }
        Request request = Request.parse(incoming.text());
        if (request == null || !isStaffCommandChat(incoming.chatId())) {
            return false;
        }
        LocalDate date = LocalDate.ofInstant(clock.instant(), venue.zone()).minusDays(request.daysBack());
        String text;
        try {
            text = formatter.format(service.build(date));
        } catch (RuntimeException e) {
            log.warn("Lunch scoreboard for {} on demand failed: {}", date, e.toString());
            text = "Табло ланча собрать не удалось (" + LunchScoreboardFormatter.escape(e.getClass().getSimpleName()) + "): попробуйте позже.";
        }
        notifications.sendStaffNotice(incoming.chatId().toString(), text);
        return true;
    }

    /** Only the configured staff chats: the same chats as for {@code /manual} and {@code /stop}. */
    private boolean isStaffCommandChat(Long chatId) {
        return sameChat(hostessChatId, chatId) || sameChat(managerChatId, chatId) || sameChat(adminChatId, chatId);
    }

    private static boolean sameChat(String configured, Long chatId) {
        return chatId != null && configured != null && !configured.isBlank() && configured.equals(chatId.toString());
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** {@code /табло}, {@code /scoreboard}, with or without {@code @bot}; «вчера» / «yesterday» as the one argument. */
    record Request(int daysBack) {
        static Request parse(String text) {
            String normalized = text == null ? "" : text.strip();
            if (!normalized.startsWith("/")) {
                return null;
            }
            String[] parts = normalized.split("\\s+", 2);
            String command = parts[0].toLowerCase(Locale.ROOT);
            int mention = command.indexOf('@');
            if (mention > 0) {
                command = command.substring(0, mention);
            }
            if (!command.equals("/табло") && !command.equals("/scoreboard")) {
                return null;
            }
            String argument = parts.length > 1 ? parts[1].strip().toLowerCase(Locale.ROOT) : "";
            boolean yesterday = argument.equals("вчера") || argument.equals("yesterday");
            return new Request(yesterday ? 1 : 0);
        }
    }
}
