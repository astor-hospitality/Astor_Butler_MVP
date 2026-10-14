package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.config.SecurityConfig;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
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
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The real security chain with the board filter in it, in front of the real board controller. */
class BoardHttpTest {

    private static final String TOKEN = "board-token-0123456789";
    private static BoardTestDatabase database;

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @Configuration @EnableWebSecurity @EnableWebMvc
    static class Harness {
        @Bean BoardProperties boardProperties() {
            BoardProperties properties = new BoardProperties();
            properties.setEnabled(true);
            properties.setToken(TOKEN);
            return properties;
        }
        @Bean BoardStream boardStream() { return new BoardStream(); }
        @Bean BoardController controller(BoardStream stream) {
            BoardQueries queries = new BoardQueries(database.jdbc(), new ObjectMapper());
            return new BoardController(queries, new VisitTurnRepository(database.jdbc(), new ObjectMapper()),
                    new BoardMetrics(queries), mock(HealthMap.class), stream);
        }
        @Bean CorsConfigurationSource cors() { return new SecurityConfig().corsConfigurationSource("http://localhost:3001"); }
        @Bean SecurityFilterChain chain(HttpSecurity http, BoardProperties board) throws Exception {
            return new SecurityConfig().securityFilterChain(http, "unit-test-internal-token-not-a-real-credential", board);
        }
    }

    @BeforeEach
    void start() {
        database = BoardTestDatabase.create();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Harness.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void close() throws Exception {
        context.close();
        database.close();
    }

    @Test
    void visitsNeedTheToken() throws Exception {
        mvc.perform(get("/api/board/v1/visits")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/board/v1/visits").header(BoardTokenFilter.HEADER, TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/board/v1/metrics").param("from", "2026-10-01T00:00:00Z").param("to", "2026-10-02T00:00:00Z")
                .header(BoardTokenFilter.HEADER, TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/board/v1/visits/1/turns").header(BoardTokenFilter.HEADER, TOKEN)).andExpect(status().isOk());
    }

    @Test
    void percentEncodedPathDoesNotSlipPast() throws Exception {
        mvc.perform(get(URI.create("/api/%62oard/v1/visits"))).andExpect(status().isUnauthorized());
    }

    @Test
    void queryTokenWorksOnlyOnTheStream() throws Exception {
        mvc.perform(get("/api/board/v1/visits").param("t", TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/board/v1/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/board/v1/stream").param("t", TOKEN)).andExpect(status().isOk());
    }
}
