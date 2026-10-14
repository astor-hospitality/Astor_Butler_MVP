package museon_online.astor_butler.domain.journey;

import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** The greeting of a known guest on /start that names the last lunch (spec §4, stage 1). Empty while the flag is off. */
@Component
public class RepeatLunchGreeting {

    private final RepeatLunchProperties properties;
    private final LastLunchService lastLunch;
    private final BusinessLunchCatalog catalog;
    private final String venueCode;

    @Autowired
    public RepeatLunchGreeting(RepeatLunchProperties properties, LastLunchService lastLunch, BusinessLunchCatalog catalog,
                               @Value("${astor.business-lunch.default-venue-code:AERIS}") String venueCode) {
        this.properties = properties;
        this.lastLunch = lastLunch;
        this.catalog = catalog;
        this.venueCode = venueCode;
    }

    public Optional<String> text(IncomingMessage incoming) {
        if (!properties.isEnabled() || incoming == null || incoming.chatId() == null) {
            return Optional.empty();
        }
        return catalog.find(venueCode)
                .flatMap(offer -> lastLunch.find(incoming.chatId(), offer))
                .map(last -> {
                    String name = incoming.firstName() == null ? "" : incoming.firstName().trim();
                    String hello = name.isEmpty() ? "С возвращением!" : "С возвращением, " + name + "!";
                    return hello + " В прошлый раз у вас был " + String.join(", ", last.titles()) + ". Повторить или собрать заново?";
                });
    }
}
