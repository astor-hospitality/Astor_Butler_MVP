package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.config.AstorLinksProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class StartupAdminNotifierTest {

    @Test
    void buildsHumanReadableStartupMessage() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "astor-butler")
                .withProperty("server.port", "8088");
        environment.setActiveProfiles("local");

        StartupAdminNotifier notifier = new StartupAdminNotifier(null, environment, new AstorLinksProperties());

        String message = notifier.buildMessage();

        assertThat(message).contains("Astor Butler / startup");
        assertThat(message).contains("Я в строю. Все работает.");
        assertThat(message).contains("App: astor-butler");
        assertThat(message).contains("Profile: local");
        assertThat(message).contains("Backend: localhost:8088");
        assertThat(message).contains("Kafka admin stream: watching");
        assertThat(message).contains("Guest: https://astor-hospitality.github.io/Astor_Butler_MVP/docs/guest-guide.html");
        assertThat(message).doesNotContain("michaelwelly.github.io");
        assertThat(message).contains("Staff Chat:");
    }

    @Test
    void guestGuideLinkComesFromConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "astor-butler")
                .withProperty("server.port", "8088");
        AstorLinksProperties links = new AstorLinksProperties();
        links.setGuestGuide("https://example.test/guest.html");

        StartupAdminNotifier notifier = new StartupAdminNotifier(null, environment, links);

        assertThat(notifier.buildMessage()).contains("Guest: https://example.test/guest.html");
    }

    @Test
    void escapesHtmlValuesFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "astor<&butler")
                .withProperty("server.port", "8088");

        StartupAdminNotifier notifier = new StartupAdminNotifier(null, environment, new AstorLinksProperties());

        String message = notifier.buildMessage();

        assertThat(message).contains("astor&lt;&amp;butler");
    }
}
