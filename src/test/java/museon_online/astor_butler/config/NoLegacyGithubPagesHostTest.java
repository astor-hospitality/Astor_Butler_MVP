package museon_online.astor_butler.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #86: GitHub Pages moved with the repository to the organisation and the old personal host answers 404.
 * Every page the bot links to is configured in {@link AstorLinksProperties}; the old host must not come back in code.
 */
class NoLegacyGithubPagesHostTest {

    private static final String LEGACY_HOST = "michaelwelly.github.io";
    private static final Path MAIN = Path.of("src/main");

    @Test
    void mainSourcesAndResourcesDoNotMentionTheLegacyGithubPagesHost() throws IOException {
        assertThat(MAIN).as("tests run from the project root").isDirectory();

        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(NoLegacyGithubPagesHostTest::mentionsLegacyHost)
                    .toList();

            assertThat(offenders)
                    .as("files under src/main still linking %s (use astor.links.* instead)", LEGACY_HOST)
                    .isEmpty();
        }
    }

    private static boolean mentionsLegacyHost(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(LEGACY_HOST);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
