package museon_online.astor_butler.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code astor.links.*}: the public pages the bot links to. One place to change when a page moves (#86):
 * the consent step, the AERIS guest card, the startup message and the pinned admin/system notes all read from here.
 */
@Component
@ConfigurationProperties(prefix = "astor.links")
@Getter
@Setter
public class AstorLinksProperties {

    /** Privacy policy the guest agrees to at the consent step; {@code ASTOR_LINK_POLICY}. */
    private String policy = "https://astor-ai.ru/policy.html";

    /** Short guest guide in the AERIS card and the startup message; {@code ASTOR_LINK_GUEST_GUIDE}. */
    private String guestGuide = "https://astor-hospitality.github.io/Astor_Butler_MVP/docs/guest-guide.html";

    /** FSM Viewer in the pinned admin and system chat notes; {@code ASTOR_LINK_FSM_VIEWER}. */
    private String fsmViewer = "https://astor-hospitality.github.io/Astor_Butler_MVP/docs/FSM_SCENARIOS_VIEWER.html";
}
