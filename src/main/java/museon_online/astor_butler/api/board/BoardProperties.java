package museon_online.astor_butler.api.board;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The closed board ({@code /api/board/**}): off by default, and closed by {@code ASTOR_BOARD_TOKEN} when on. */
@Component
@ConfigurationProperties(prefix = "astor.board")
@Getter
@Setter
public class BoardProperties {

    private boolean enabled = false;
    /** At least 16 characters; a shorter or blank token closes every board path. */
    private String token = "";
    /** The Concierge readiness URL for the health map; blank shows the Concierge yellow. */
    private String conciergeHealthUrl = "http://astor-concierge:8080/readyz";
}
