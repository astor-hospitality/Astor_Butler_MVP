package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.config.AstorLinksProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The pinned admin and system notes link the FSM Viewer from {@code astor.links.fsm-viewer} (#86). */
class OperationalChatPreviewNotifierTest {

    @Test
    void pinnedAdminAndSystemNotesLinkTheConfiguredFsmViewer() {
        AstorLinksProperties links = new AstorLinksProperties();
        links.setFsmViewer("https://example.test/fsm.html");

        OperationalChatPreviewNotifier notifier = new OperationalChatPreviewNotifier(null, links);

        assertThat(notifier.adminPreview()).contains("<a href=\"https://example.test/fsm.html\">FSM Viewer</a>");
        assertThat(notifier.systemPreview()).contains("<a href=\"https://example.test/fsm.html\">FSM Viewer</a>");
    }

    @Test
    void defaultFsmViewerIsTheOrganisationGithubPage() {
        OperationalChatPreviewNotifier notifier = new OperationalChatPreviewNotifier(null, new AstorLinksProperties());

        assertThat(notifier.adminPreview())
                .contains("https://astor-hospitality.github.io/Astor_Butler_MVP/docs/FSM_SCENARIOS_VIEWER.html")
                .doesNotContain("michaelwelly.github.io");
        assertThat(notifier.systemPreview())
                .contains("https://astor-hospitality.github.io/Astor_Butler_MVP/docs/FSM_SCENARIOS_VIEWER.html")
                .doesNotContain("michaelwelly.github.io");
    }
}
