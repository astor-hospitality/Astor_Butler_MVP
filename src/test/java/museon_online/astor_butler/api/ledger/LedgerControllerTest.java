package museon_online.astor_butler.api.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.LedgerProperties;
import museon_online.astor_butler.domain.ledger.LedgerTestDatabase;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.TurnChannel;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LedgerControllerTest {

    private LedgerTestDatabase database;
    private VisitTurnRepository turns;
    private LedgerProperties properties;
    private LedgerController controller;

    @BeforeEach
    void setUp() {
        database = LedgerTestDatabase.create();
        turns = new VisitTurnRepository(database.jdbc(), new ObjectMapper());
        properties = new LedgerProperties();
        properties.setEnabled(true);
        controller = new LedgerController(new LedgerWriter(turns, mock(GuestVisitRepository.class), properties,
                mock(ApplicationEventPublisher.class), Clock.systemUTC()));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void conciergeTurnWithoutConsentKeepsNoText() {
        ResponseEntity<Map<String, Object>> response = controller.appendTurn(new VisitTurnDraft(null, "c-1", TurnSide.GUEST,
                TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "хочу столик на двоих", Map.of("hidden", true), null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = ((Number) response.getBody().get("id")).longValue();
        assertThat(database.jdbc().queryForObject("SELECT text FROM visit_turns WHERE id = ?", String.class, id)).isNull();
        assertThat(turns.ofSession("c-1")).singleElement().satisfies(turn -> assertThat(turn.payload()).containsEntry("hidden", true));
    }

    @Test
    void conciergeJsonWithoutConsentIsStoredWithoutText() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).build();

        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ledger/v1/turns")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"c-1\",\"side\":\"GUEST\",\"channel\":\"CONCIERGE_TG\",\"kind\":\"MESSAGE\","
                                + "\"text\":\"хочу столик на двоих\",\"payload\":{\"hidden\":true}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        long id = new ObjectMapper().readTree(body).get("id").asLong();
        assertThat(database.jdbc().queryForObject("SELECT text FROM visit_turns WHERE id = ?", String.class, id)).isNull();
        assertThat(database.jdbc().queryForObject("SELECT session_id FROM visit_turns WHERE id = ?", String.class, id)).isEqualTo("c-1");
    }

    @Test
    void linksASessionToTheVisit() {
        controller.appendTurn(new VisitTurnDraft(null, "c-1", TurnSide.CONCIERGE, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE,
                "уточню у ресторана", null, null));

        ResponseEntity<Map<String, Object>> response = controller.linkSession("c-1", new LedgerController.LinkRequest(7L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("linked", 1);
        assertThat(turns.ofVisit(7L)).hasSize(1);
    }

    @Test
    void turnWithoutSideIsRejected() {
        ResponseEntity<Map<String, Object>> response = controller.appendTurn(new VisitTurnDraft(7L, null, null,
                TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "x", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void disabledLedgerAnswersUnavailable() {
        properties.setEnabled(false);

        ResponseEntity<Map<String, Object>> response = controller.appendTurn(new VisitTurnDraft(7L, null, TurnSide.CONCIERGE,
                TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "x", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(turns.ofVisit(7L)).isEmpty();
    }
}
