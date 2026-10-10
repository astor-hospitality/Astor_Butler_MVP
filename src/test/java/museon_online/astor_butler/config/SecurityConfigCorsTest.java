package museon_online.astor_butler.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigCorsTest {

    private CorsConfiguration forPath(CorsConfigurationSource source, String path) {
        return source.getCorsConfiguration(new MockHttpServletRequest("POST", path));
    }

    @Test
    void emptyAllowListMeansSameOriginOnly() {
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource("");
        CorsConfiguration configuration = forPath(source, "/api/astor/messages");
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).isEmpty();
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void csvAllowListCoversTheWebChatPaths() {
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(" https://c3ag.ru, https://www.c3ag.ru ,");
        for (String path : new String[]{"/api/astor/messages", "/api/messages", "/api/concierge/messages"}) {
            CorsConfiguration configuration = forPath(source, path);
            assertThat(configuration).as(path).isNotNull();
            assertThat(configuration.getAllowedOrigins()).containsExactly("https://c3ag.ru", "https://www.c3ag.ru");
            assertThat(configuration.checkOrigin("https://c3ag.ru")).isEqualTo("https://c3ag.ru");
            assertThat(configuration.checkOrigin("https://evil.example")).isNull();
            assertThat(configuration.getAllowedMethods()).contains("POST", "OPTIONS");
            assertThat(configuration.getAllowCredentials()).isFalse();
        }
        assertThat(forPath(source, "/swagger-ui/index.html")).isNull();
    }
}
