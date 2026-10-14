package museon_online.astor_butler.domain.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** «Не пишите мне» / «больше не писать»: the guest switches off bot-first messages; answers to the guest stay. */
@Component
public class OptOutCommand {

    public static final String REPLY = "Хорошо, сам писать не буду. Если что-то понадобится — просто напишите.";

    private static final Logger log = LoggerFactory.getLogger(OptOutCommand.class);
    private static final Pattern OPT_OUT = Pattern.compile(
            "(больше\\s+)?не\\s+пиши(те)?\\s+(мне|сам[иа]?)\\b|больше\\s+не\\s+(пиши(те)?|писать)\\s*[.!]?$|отпиши(те)?\\s+меня|хватит\\s+(мне\\s+)?писать", Pattern.UNICODE_CHARACTER_CLASS);

    private final GuestVisitRepository visits;
    private final UsefulnessPolicyProperties properties;
    private final JourneyEvents events;

    public OptOutCommand(GuestVisitRepository visits, UsefulnessPolicyProperties properties, JourneyEvents events) {
        this.visits = visits;
        this.properties = properties;
        this.events = events;
    }

    public Optional<String> tryHandle(Long chatId, String text) {
        if (!properties.isEnabled() || chatId == null || chatId <= 0 || text == null) {
            return Optional.empty();
        }
        if (!OPT_OUT.matcher(text.toLowerCase(Locale.ROOT).trim()).find()) {
            return Optional.empty();
        }
        try {
            visits.optOut(chatId);
        } catch (RuntimeException e) {
            log.warn("Opt-out not stored: chat={}, reason={}", chatId, e.toString());
        }
        events.onChat(chatId, VisitEventKind.OPTED_OUT, JourneyEvents.GUEST, null);
        return Optional.of(REPLY);
    }
}
