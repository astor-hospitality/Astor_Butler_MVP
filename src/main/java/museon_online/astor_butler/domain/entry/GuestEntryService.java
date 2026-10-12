package museon_online.astor_butler.domain.entry;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * Who brought the guest in (#128). Seen by the router on every message: a {@code /start} with a staff parameter
 * writes one row to {@code guest_entry_events} and one line to the system chat, before consent or the lunch answer.
 * The hostess card of a request made within the session window then says «Привёл(а): …».
 * <p>The attribution is a note in the journal, nothing more: no bonuses, no ratings. An id the directory does not know
 * is written as given and shown as «стойка/неизвестно»; nothing here ever stops the guest.
 */
@Service
@Slf4j
public class GuestEntryService {

    public static final String HOST_STAND_NAME = "стойка хоста";
    public static final String UNKNOWN_NAME = "стойка/неизвестно";

    private final StaffHandoffProperties properties;
    private final GuestEntryEventRepository repository;
    private final TelegramSystemNotifier systemNotifier;
    private Clock clock = Clock.systemUTC();

    public GuestEntryService(StaffHandoffProperties properties, GuestEntryEventRepository repository, TelegramSystemNotifier systemNotifier) {
        this.properties = properties;
        this.repository = repository;
        this.systemNotifier = systemNotifier;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /**
     * Writes the entry when {@code text} is a {@code /start} with a staff parameter; empty, and nothing written,
     * for any other message or with the feature off. Never throws: a failed write is a log line, not a guest's problem.
     */
    public Optional<StaffHandoffLink> rememberStart(IncomingMessage incoming, String text) {
        if (!properties.isEnabled() || incoming == null || incoming.chatId() == null) {
            return Optional.empty();
        }
        Optional<StaffHandoffLink> parsed = StaffHandoffLink.parse(text);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        StaffHandoffLink link = parsed.get();
        try {
            repository.insert(properties.tenant(), incoming.chatId(), link.staffId(), link.tableCode(),
                    GuestEntryEvent.SOURCE_STAFF_HANDOFF, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Guest entry event was not written: chatId={}, staffId={}, reason={}", incoming.chatId(), link.staffId(), e.toString());
        }
        String who = displayName(link.staffId());
        log.info("AUDIT Guest entry: chatId={}, staffId={}, table={}, lunch={}", incoming.chatId(), link.staffId(), link.tableCode(), link.lunch());
        trace(traceLine(incoming, link, who));
        return parsed;
    }

    /** The display name of the staff member who brought the guest of this chat in, while the entry is within the session window. */
    public Optional<String> responsibleFor(Long chatId) {
        if (!properties.isEnabled() || chatId == null) {
            return Optional.empty();
        }
        try {
            return repository.latestForChat(chatId, clock.instant().minus(properties.sessionWindow()))
                    .map(event -> displayName(event.staffId()));
        } catch (RuntimeException e) {
            log.warn("Guest entry lookup failed: chatId={}, reason={}", chatId, e.toString());
            return Optional.empty();
        }
    }

    /** «Анна» from the directory; «стойка хоста» for the host stand QR; «стойка/неизвестно» for an id the directory does not know. */
    public String displayName(String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return UNKNOWN_NAME;
        }
        if (StaffHandoffLink.HOST_STAND.equalsIgnoreCase(staffId)) {
            return HOST_STAND_NAME;
        }
        return directoryName(staffId).orElse(UNKNOWN_NAME);
    }

    /** True when the directory has this id or it is the host stand: the {@code /ссылка} command warns about anything else. */
    public boolean known(String staffId) {
        return staffId != null && (StaffHandoffLink.HOST_STAND.equalsIgnoreCase(staffId) || directoryName(staffId).isPresent());
    }

    private Optional<String> directoryName(String staffId) {
        try {
            return repository.staffDisplayName(properties.tenant(), staffId)
                    .map(String::trim)
                    .filter(name -> !name.isBlank());
        } catch (RuntimeException e) {
            log.warn("Staff directory lookup failed: staffId={}, reason={}", staffId, e.toString());
            return Optional.empty();
        }
    }

    /** One line, no phone: «Вход гостя Наталья (chat 123): привёл(а) Анна (anna), стол 5, бизнес-ланч». */
    static String traceLine(IncomingMessage incoming, StaffHandoffLink link, String who) {
        StringBuilder line = new StringBuilder("Вход гостя ")
                .append(guestName(incoming))
                .append(" (chat ").append(incoming.chatId()).append("): привёл(а) ")
                .append(who).append(" (").append(link.staffId()).append(")");
        if (link.tableCode() != null && !link.tableCode().isBlank()) {
            line.append(", стол ").append(link.tableCode());
        }
        if (link.lunch()) {
            line.append(", бизнес-ланч");
        }
        return line.toString();
    }

    private static String guestName(IncomingMessage incoming) {
        String first = incoming.firstName() == null ? "" : incoming.firstName().strip();
        String last = incoming.lastName() == null ? "" : incoming.lastName().strip();
        String name = (first + " " + last).strip();
        if (!name.isBlank()) {
            return name;
        }
        if (incoming.username() != null && !incoming.username().isBlank()) {
            return "@" + incoming.username().strip();
        }
        return "гость";
    }

    private void trace(String line) {
        try {
            systemNotifier.sendSystemNotice(line);
        } catch (RuntimeException e) {
            log.debug("Guest entry trace was not sent to the system chat: {}", e.toString());
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
