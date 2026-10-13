package museon_online.astor_butler.domain.journey.talk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads {@code business-lunch/aeris-venue.json} and finds which fact a guest's question is about. */
@Component
@Slf4j
public class VenueFactsCatalog {

    static final String RESOURCE = "business-lunch/aeris-venue.json";

    /** What a guest can ask about the venue. */
    public enum Topic {
        PARKING("парков|машин[уа]\\s+оставить|где\\s+встать"),
        KIDS("дет(ьми|ей|ск)|ребён|ребен|с\\s+малыш"),
        WIFI("wi-?fi|вай-?фай|интернет|пароль\\s+от"),
        WINE("вин[оау]\\s+к|какое\\s+вино|что\\s+выпить\\s+к"),
        EVENTS("афиш|мероприят|концерт|что\\s+у\\s+вас\\s+(будет|идёт|идет)|событи");

        private final Pattern pattern;

        Topic(String regex) {
            this.pattern = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
        }

        static Optional<Topic> of(String text) {
            String said = text == null ? "" : text.toLowerCase(Locale.ROOT);
            for (Topic topic : values()) {
                if (topic.pattern.matcher(said).find()) {
                    return Optional.of(topic);
                }
            }
            return Optional.empty();
        }
    }

    private final VenueFacts facts;

    public VenueFactsCatalog(VenueFacts facts) {
        this.facts = facts == null ? VenueFacts.none() : facts;
    }

    @Autowired
    public VenueFactsCatalog(ObjectMapper objectMapper) {
        this(load(objectMapper));
    }

    private static VenueFacts load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            return VenueFacts.none();
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, VenueFacts.class);
        } catch (Exception e) {
            log.warn("Venue facts not read from {}: {}", RESOURCE, e.toString());
            return VenueFacts.none();
        }
    }

    public Optional<String> fact(Topic topic) {
        String value = switch (topic) {
            case PARKING -> facts.parking();
            case KIDS -> facts.kids();
            case WIFI -> facts.wifi();
            case WINE -> facts.winePairing();
            case EVENTS -> facts.events();
        };
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
