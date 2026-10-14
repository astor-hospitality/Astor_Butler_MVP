package museon_online.astor_butler.api.capability;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.tools.VenueInfoTool;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityControllerTest {

    private CapabilityProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new CapabilityProperties();
        properties.setEnabled(true);
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(new VenueInfoTool(new VenueFactsCatalog(VenueFacts.none()), properties)),
                mock(LedgerWriter.class), properties);
        mvc = MockMvcBuilders.standaloneSetup(new CapabilityController(registry)).build();
    }

    @Test
    void listsToolsAsJsonSchemas() throws Exception {
        mvc.perform(get("/api/capabilities/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("venue_info"))
                .andExpect(jsonPath("$[0].description").isString())
                .andExpect(jsonPath("$[0].parameters.type").value("object"))
                .andExpect(jsonPath("$[0].parameters.properties").isMap())
                .andExpect(jsonPath("$[0].commitment").value(false));
    }

    @Test
    void invokesATool() throws Exception {
        mvc.perform(post("/api/capabilities/v1/venue_info").contentType("application/json")
                        .content("{\"context\":{\"chatId\":501,\"sessionId\":\"c-1\"},\"arguments\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.facts.name").value("AERIS"));
    }

    @Test
    void unknownToolIsAnAnswerNotAnHttpError() throws Exception {
        mvc.perform(post("/api/capabilities/v1/book_spaceship").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void switchedOffApiListsNothingAndAnswersUnavailable() throws Exception {
        properties.setEnabled(false);

        mvc.perform(get("/api/capabilities/v1/tools")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/capabilities/v1/venue_info").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("UNAVAILABLE"));
    }
}
