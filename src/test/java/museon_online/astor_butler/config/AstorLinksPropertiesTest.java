package museon_online.astor_butler.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code astor.links.*} (#86): the live defaults in application.yaml, and every link replaceable from the environment. */
class AstorLinksPropertiesTest {

    private static final String POLICY = "https://astor-ai.ru/policy.html";
    private static final String GUEST_GUIDE = "https://astor-hospitality.github.io/Astor_Butler_MVP/docs/guest-guide.html";
    private static final String FSM_VIEWER = "https://astor-hospitality.github.io/Astor_Butler_MVP/docs/FSM_SCENARIOS_VIEWER.html";

    @Test
    void applicationYamlDefaultsPointAtThePagesThatAnswer() throws IOException {
        AstorLinksProperties links = bindFromApplicationYaml(Map.of());

        assertThat(links.getPolicy()).isEqualTo(POLICY);
        assertThat(links.getGuestGuide()).isEqualTo(GUEST_GUIDE);
        assertThat(links.getFsmViewer()).isEqualTo(FSM_VIEWER);
        // The Java defaults (what a bare `new AstorLinksProperties()` gives a test) match the yaml.
        assertThat(new AstorLinksProperties()).usingRecursiveComparison().isEqualTo(links);
    }

    @Test
    void everyLinkIsReplaceableFromTheEnvironment() throws IOException {
        AstorLinksProperties links = bindFromApplicationYaml(Map.of(
                "ASTOR_LINK_POLICY", "https://example.test/policy.html",
                "ASTOR_LINK_GUEST_GUIDE", "https://example.test/guest.html",
                "ASTOR_LINK_FSM_VIEWER", "https://example.test/fsm.html"));

        assertThat(links.getPolicy()).isEqualTo("https://example.test/policy.html");
        assertThat(links.getGuestGuide()).isEqualTo("https://example.test/guest.html");
        assertThat(links.getFsmViewer()).isEqualTo("https://example.test/fsm.html");
    }

    @Test
    void bindsAsASpringBeanUnderAstorLinks() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(
                        "astor.links.policy=https://example.test/policy.html",
                        "astor.links.guest-guide=https://example.test/guest.html",
                        "astor.links.fsm-viewer=https://example.test/fsm.html")
                .run(context -> {
                    AstorLinksProperties links = context.getBean(AstorLinksProperties.class);
                    assertThat(links.getPolicy()).isEqualTo("https://example.test/policy.html");
                    assertThat(links.getGuestGuide()).isEqualTo("https://example.test/guest.html");
                    assertThat(links.getFsmViewer()).isEqualTo("https://example.test/fsm.html");
                });
    }

    /** application.yaml as the application reads it, with only the given variables in the environment. */
    private static AstorLinksProperties bindFromApplicationYaml(Map<String, String> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test-environment", new HashMap<>(environment)));
        for (PropertySource<?> document : new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yaml"))) {
            sources.addLast(document);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("astor.links", Bindable.of(AstorLinksProperties.class)).get();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AstorLinksProperties.class)
    static class Config {
    }
}
