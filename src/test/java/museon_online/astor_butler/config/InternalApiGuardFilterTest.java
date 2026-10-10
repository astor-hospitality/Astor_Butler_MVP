package museon_online.astor_butler.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real {@link SecurityConfig} chain (firewall, CORS filter, guard) through MockMvc with a stub controller
 * behind it, so the tests prove both the filter logic and its wiring.
 */
class InternalApiGuardFilterTest {
    private static final String TOKEN = "unit-test-internal-token-not-a-real-credential";
    private static final String HEADER = InternalApiGuardFilter.HEADER;
    private static String configuredToken = TOKEN;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private Stub stub;

    @RestController
    static class Stub {
        final List<String> hits = new CopyOnWriteArrayList<>();

        @PostMapping(value = "/api/messages", consumes = "application/json")
        Map<String, Object> messages(@RequestBody Map<String, Object> body, HttpServletRequest request) {
            hits.add("POST /api/messages");
            return Map.of("echoChannel", String.valueOf(body.get("channel")), "echoText", String.valueOf(body.get("text")));
        }

        @RequestMapping("/**")
        Map<String, String> any(HttpServletRequest request) {
            hits.add(request.getMethod() + " " + request.getRequestURI());
            return Map.of("path", request.getRequestURI());
        }
    }

    @Configuration @EnableWebSecurity @EnableWebMvc
    static class Harness {
        @Bean Stub stub() { return new Stub(); }
        @Bean CorsConfigurationSource cors() { return new SecurityConfig().corsConfigurationSource("http://localhost:3001"); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return new SecurityConfig().securityFilterChain(http, configuredToken);
        }
    }

    @BeforeEach void start() { open(TOKEN); }

    private void open(String token) {
        configuredToken = token;
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Harness.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
        stub = context.getBean(Stub.class);
    }

    @AfterEach void close() { context.close(); configuredToken = TOKEN; }

    private static String web(String text) {
        return "{\"channel\":\"WEB\",\"text\":\"" + text + "\",\"payload\":{\"sessionId\":\"web-a-b\"}}";
    }

    @Test void internalPathWithoutTokenIsUnauthorizedAndNeverReachesController() throws Exception {
        mvc.perform(get("/api/bookings/table-reservations/telegram/123456"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.details.reason").value("INTERNAL_TOKEN_REQUIRED"));
        assertThat(stub.hits).isEmpty();
    }

    @Test void internalPathWithWrongTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/bookings/table-reservations/telegram/123456").header(HEADER, TOKEN + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/table-reservations/telegram/123456").header(HEADER, ""))
                .andExpect(status().isUnauthorized());
        assertThat(stub.hits).isEmpty();
    }

    @Test void internalPathWithRightTokenReachesController() throws Exception {
        mvc.perform(get("/api/bookings/table-reservations/telegram/123456").header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/api/bookings/table-reservations/telegram/123456"));
        mvc.perform(post("/api/bookings/table-reservations/42/confirm").header(HEADER, TOKEN))
                .andExpect(status().isOk());
        assertThat(stub.hits).containsExactly(
                "GET /api/bookings/table-reservations/telegram/123456",
                "POST /api/bookings/table-reservations/42/confirm");
    }

    @Test void everyGuardedPrefixRequiresTheToken() throws Exception {
        for (String path : List.of(
                "/api/bookings/table-reservations",
                "/api/fsm/telegram/1/state",
                "/api/admin/anything",
                "/api/internal/anything",
                "/api/concierge/requests/telegram/1",
                "/actuator/metrics",
                "/actuator/env")) {
            mvc.perform(put(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        assertThat(stub.hits).isEmpty();
    }

    @Test void carveOutsInsideGuardedPrefixesStayOpen() throws Exception {
        // These carry their own authentication (staff JWT chain, relay header) or are liveness probes.
        for (String path : List.of(
                "/api/admin/staff-tasks/dashboard",
                "/api/admin/staff/members/anna",
                "/api/internal/glasses/transcript",
                "/api/internal/glasses/staff-tasks",
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/prometheus")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        assertThat(stub.hits).hasSize(7);
    }

    @Test void glassesRelayEndpointsAreReachableWithoutTheInternalTokenForPosts() throws Exception {
        // The isolated glasses runtime sends only X-Astor-Relay-Token; both relay controllers verify it themselves.
        for (String path : List.of("/api/internal/glasses/transcript", "/api/internal/glasses/staff-tasks")) {
            mvc.perform(post(path).contentType("application/json").content("{}").header("X-Astor-Relay-Token", "runtime"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/internal/other").contentType("application/json").content("{}")
                .header("X-Astor-Relay-Token", "runtime")).andExpect(status().isUnauthorized());
        assertThat(stub.hits).containsExactly("POST /api/internal/glasses/transcript", "POST /api/internal/glasses/staff-tasks");
    }

    @Test void publicPathsAreUntouched() throws Exception {
        for (String path : List.of(
                "/api/glasses/capabilities",
                "/api/glasses/assist",
                "/api/glasses/media/abc",
                "/api/chat/speak",
                "/api/astor/messages",
                "/api/web/sessions",
                "/api/staff/login-config",
                "/api/auth/me")) {
            mvc.perform(get(path)).andExpect(status().isOk());
            mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isOk());
        }
        assertThat(stub.hits).hasSize(16);
    }

    @Test void messageGatewayWebChannelPassesWithoutTokenAndBodyIsReplayedIntact() throws Exception {
        mvc.perform(post("/api/messages").contentType("application/json").content(web("Привет, столик на двоих")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.echoChannel").value("WEB"))
                .andExpect(jsonPath("$.echoText").value("Привет, столик на двоих"));
        assertThat(stub.hits).containsExactly("POST /api/messages");
    }

    @Test void messageGatewayWithoutChannelIsLeftToTheControllerWhichDefaultsToWeb() throws Exception {
        mvc.perform(post("/api/messages").contentType("application/json").content("{\"text\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.echoChannel").value("null"));
        mvc.perform(post("/api/messages").contentType("application/json").content("{\"channel\":null,\"text\":\"hi\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/messages").contentType("application/json").content("{\"channel\":\" web \",\"text\":\"hi\"}"))
                .andExpect(status().isOk());
        assertThat(stub.hits).hasSize(3);
    }

    @Test void messageGatewayTelegramAndInternalWithoutTokenAreForbidden() throws Exception {
        for (String channel : List.of("TELEGRAM", "INTERNAL", "telegram", " Telegram ")) {
            mvc.perform(post("/api/messages").contentType("application/json")
                    .content("{\"channel\":\"" + channel + "\",\"chatId\":123456,\"text\":\"Изменить / отменить\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.details.reason").value("INTERNAL_CHANNEL_REQUIRES_TOKEN"))
                    .andExpect(jsonPath("$.details.channel").value(channel.trim().toUpperCase()));
        }
        assertThat(stub.hits).isEmpty();
    }

    @Test void messageGatewayTelegramWithRightTokenReachesController() throws Exception {
        mvc.perform(post("/api/messages").contentType("application/json").header(HEADER, TOKEN)
                .content("{\"channel\":\"TELEGRAM\",\"chatId\":123456,\"text\":\"/start\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.echoChannel").value("TELEGRAM"))
                .andExpect(jsonPath("$.echoText").value("/start"));
        assertThat(stub.hits).containsExactly("POST /api/messages");
    }

    @Test void messageGatewayWrongTokenIsUnauthorizedEvenForWeb() throws Exception {
        mvc.perform(post("/api/messages").contentType("application/json").header(HEADER, "wrong-token-value-1234")
                .content(web("hi")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/messages").contentType("application/json").header(HEADER, "wrong-token-value-1234")
                .content("{\"channel\":\"TELEGRAM\",\"chatId\":1}"))
                .andExpect(status().isUnauthorized());
        assertThat(stub.hits).isEmpty();
    }

    @Test void messageGatewayMalformedBodyIsLeftToTheController() throws Exception {
        // Not JSON: cannot select a channel, so the controller (here Spring MVC) answers 400, not 401/403.
        mvc.perform(post("/api/messages").contentType("application/json").content("{\"channel\":\"TELEGRAM\""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/messages").contentType("application/json").content("[\"TELEGRAM\"]"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages")).andExpect(status().isOk()); // no body: nothing to gate, MVC decides
    }

    @Test void messageGatewayOversizedAnonymousBodyIsRejectedBeforeParsing() throws Exception {
        String huge = "{\"channel\":\"WEB\",\"text\":\"" + "x".repeat(InternalApiGuardFilter.MAX_PEEK_BYTES) + "\"}";
        mvc.perform(post("/api/messages").contentType("application/json").content(huge))
                .andExpect(status().isPayloadTooLarge());
        assertThat(stub.hits).isEmpty();
    }

    @Test void blankTokenFailsClosedButKeepsTheWebWidgetWorking() throws Exception {
        context.close();
        open("");
        mvc.perform(get("/api/bookings/table-reservations/telegram/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/table-reservations/telegram/1").header(HEADER, TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/table-reservations/telegram/1").header(HEADER, "")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/messages").contentType("application/json")
                .content("{\"channel\":\"TELEGRAM\",\"chatId\":1}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/messages").contentType("application/json")
                .content("{\"channel\":\"TELEGRAM\",\"chatId\":1}").header(HEADER, TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/messages").contentType("application/json").content(web("hi"))).andExpect(status().isOk());
        mvc.perform(get("/api/glasses/capabilities")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        assertThat(stub.hits).containsExactly("POST /api/messages", "GET /api/glasses/capabilities", "GET /actuator/health");
    }

    @Test void shortTokenIsTreatedAsUnset() throws Exception {
        context.close();
        open("short");
        mvc.perform(get("/api/fsm/telegram/1/state").header(HEADER, "short")).andExpect(status().isUnauthorized());
        assertThat(stub.hits).isEmpty();
    }

    @Test void pathTricksDoNotReachInternalControllers() throws Exception {
        // The security firewall or the matcher must stop these before the controller; never 200.
        for (String path : List.of(
                "/api/bookings/..;/bookings/table-reservations",
                "/api/bookings/%2e%2e/bookings/table-reservations",
                "/api//bookings/table-reservations",
                "/api/bookings")) {
            int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isNotEqualTo(200);
        }
        assertThat(stub.hits).isEmpty();
    }
}
