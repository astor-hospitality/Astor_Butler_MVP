package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.config.SecurityConfig;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.SummaryProperties;
import museon_online.astor_butler.domain.summary.VisitSummaryRepository;
import museon_online.astor_butler.domain.summary.VisitSummaryService;
import museon_online.astor_butler.model.ModelGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
        @Bean VisitSummaryRepository summaries() { return new VisitSummaryRepository(database.jdbc(), new ObjectMapper()); }
        @Bean AgentLessonRepository lessons() { return new AgentLessonRepository(database.jdbc()); }
        @Bean VisitSummaryService summaryService(VisitSummaryRepository summaries, AgentLessonRepository lessons) {
            return new VisitSummaryService(new VisitTurnRepository(database.jdbc(), new ObjectMapper()),
                    new GuestVisitRepository(database.jdbc()), summaries, lessons, mock(ModelGateway.class),
                    new SummaryProperties(), new ObjectMapper(), new GuestJourneyProperties());
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

    @Test
    void lessonsAreDecidedOverHttpWithTheToken() throws Exception {
        long visit = database.visit(database.reservation("Мария", null, null, java.time.Instant.now()), null, "AFTER",
                java.time.Instant.now(), 1);
        AgentLessonRepository lessons = context.getBean(AgentLessonRepository.class);
        lessons.propose(visit, "BUTLER", "не переспрашивать");
        long id = lessons.withStatus(museon_online.astor_butler.domain.summary.AgentLesson.Status.PROPOSED).getFirst().id();

        mvc.perform(post("/api/board/v1/lessons/" + id + "/accept")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/board/v1/lessons").param("status", "PROPOSED").header(BoardTokenFilter.HEADER, TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("не переспрашивать"));
        mvc.perform(post("/api/board/v1/lessons/" + id + "/accept").header(BoardTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"не переспрашивать время\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/board/v1/visits/" + visit + "/next-time").header(BoardTokenFilter.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"любит окно\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextTimeGuest[0]").value("любит окно"));
        mvc.perform(get("/api/board/v1/visits/" + visit + "/summary").header(BoardTokenFilter.HEADER, TOKEN))
                .andExpect(status().isOk());
        mvc.perform(post("/api/board/v1/visits/" + visit + "/summary").header(BoardTokenFilter.HEADER, TOKEN))
                .andExpect(status().isServiceUnavailable());
    }
}
