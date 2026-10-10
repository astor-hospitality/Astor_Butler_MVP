package museon_online.astor_butler.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @RestController
    static class Echo {
        @PostMapping("/api/astor/messages")
        String ok() {
            return "ok";
        }
    }

    /** Same pipeline as production with server.forward-headers-strategy=framework: forwarded headers, then CORS. */
    private MockMvc behindProxy(String allowedOrigins) {
        return MockMvcBuilders.standaloneSetup(new Echo())
                .addFilters(new ForwardedHeaderFilter(), new CorsFilter(new SecurityConfig().corsConfigurationSource(allowedOrigins)))
                .build();
    }

    @Test
    void emptyAllowListAcceptsTheSiteOriginBehindTheProxyBecauseItIsSameOrigin() throws Exception {
        MockMvc mvc = behindProxy("");
        // Caddy -> nginx -> Butler: the servlet request itself is plain http on an internal host.
        mvc.perform(post("/api/astor/messages")
                        .header("Host", "c3ag.ru")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "c3ag.ru")
                        .header("Origin", "https://c3ag.ru"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/astor/messages")
                        .header("Host", "c3ag.ru")
                        .header("X-Forwarded-Proto", "https")
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        // Without the forwarded scheme the same request would look cross-origin (http vs https) and be refused.
        mvc.perform(post("/api/astor/messages")
                        .header("Host", "c3ag.ru")
                        .header("Origin", "https://c3ag.ru"))
                .andExpect(status().isForbidden());
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
