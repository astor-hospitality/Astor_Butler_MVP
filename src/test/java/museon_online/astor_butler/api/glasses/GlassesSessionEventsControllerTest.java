package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.ModelGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlassesSessionEventsControllerTest {
    private static final String TOKEN = "unit-test-only-not-a-real-credential";
    private static final String BATCH = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private static final String SESSION = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
    private final ObjectMapper mapper = new ObjectMapper();
    private final GlassesAssistService service = new GlassesAssistService(mock(ModelGateway.class), true, 1000);
    private final GlassesSessionJournal journal = new GlassesSessionJournal(GlassesS3Storage.disabled(), Clock.systemUTC(), false);
    private final GlassesSessionEventsController controller = new GlassesSessionEventsController(access(), service, journal, mapper);

    @AfterEach void close() { service.close(); }

    private GlassesAccess access() {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
            return new GlassesAccess(hash, "test-venue", "test-staff", Instant.now().plusSeconds(60).toString());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private MockHttpServletRequest request(String content, String bearer) {
        var request = new MockHttpServletRequest("POST", "/api/glasses/session-events");
        if (bearer != null) request.addHeader("Authorization", "Bearer " + bearer);
        request.setContentType("application/json");
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String batch(List<Map<String, ?>> events) throws Exception {
        return mapper.writeValueAsString(Map.of("requestId", BATCH, "sessionId", SESSION, "scenarioCode", "BUSINESS_LUNCH_TWO", "events", events));
    }

    private Map<String, ?> event(String type, String stage) {
        return stage == null ? Map.of("at", Instant.now().toString(), "type", type)
                : Map.of("at", Instant.now().toString(), "type", type, "stageCode", stage);
    }

    private String code(Object body) {
        return ((GlassesController.ErrorResponse) body).error().get("code");
    }

    @Test void acceptsABoundedBatchOnceAndEchoesTheSameAnswerOnRetry() throws Exception {
        String body = batch(List.of(event("STEP_STARTED", "PLACE_SETTINGS"), event("CALL_STARTED", null),
                Map.of("at", Instant.now().toString(), "type", "CALL_ENDED", "note", "гость звонил")));
        var first = controller.events(request(body, TOKEN));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var accepted = (GlassesSessionEventsController.Accepted) first.getBody();
        assertThat(accepted.accepted()).isEqualTo(3);
        assertThat(accepted.sessionId()).isEqualTo(SESSION);
        assertThat(controller.events(request(body, TOKEN)).getStatusCode().value()).isEqualTo(200);
        var session = journal.session(new GlassesAccess.Scope("test-venue", "test-staff"), SESSION);
        assertThat(session.entries()).hasSize(3);
        assertThat(session.entries().get(2).note()).isEqualTo("гость звонил");
        assertThat(session.entries().get(2).requestId()).isNull();
    }

    @Test void aDifferentBatchUnderTheSameRequestIdIsAConflict() throws Exception {
        controller.events(request(batch(List.of(event("STEP_STARTED", "TABLE_PREPARE"))), TOKEN));
        var result = controller.events(request(batch(List.of(event("STEP_DONE", "TABLE_PREPARE"))), TOKEN));
        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(code(result.getBody())).isEqualTo("REQUEST_ID_CONFLICT");
    }

    @Test void requiresTheBearerBeforeReadingTheBody() {
        var result = controller.events(request("not json", null));
        assertThat(result.getStatusCode().value()).isEqualTo(401);
        assertThat(result.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test void rejectsUnknownTypesStagesFieldsStaleTimesAndEmptyOrOversizedBatches() throws Exception {
        var rejected = List.of(
                batch(List.of(event("TASK_ACKED", null))),
                batch(List.of(event("STEP_STARTED", "LIVE_TASK"))),
                batch(List.of(event("STEP_STARTED", "SHIFT_ASSIST"))),
                batch(List.of(Map.of("at", Instant.now().toString(), "type", "STEP_DONE", "taskId", "7"))),
                batch(List.of(Map.of("at", "2020-01-01T00:00:00Z", "type", "STEP_DONE"))),
                batch(List.of(Map.of("at", "yesterday", "type", "STEP_DONE"))),
                batch(List.of()),
                batch(java.util.Collections.nCopies(51, event("STEP_DONE", null))),
                mapper.writeValueAsString(Map.of("requestId", BATCH, "sessionId", SESSION, "scenarioCode", "LIVE", "events", List.of(event("STEP_DONE", null)))),
                mapper.writeValueAsString(Map.of("requestId", BATCH, "sessionId", SESSION, "scenarioCode", "BUSINESS_LUNCH_TWO", "staffId", "x", "events", List.of(event("STEP_DONE", null)))),
                mapper.writeValueAsString(Map.of("requestId", "nope", "sessionId", SESSION, "scenarioCode", "BUSINESS_LUNCH_TWO", "events", List.of(event("STEP_DONE", null)))));
        for (String body : rejected) {
            var result = controller.events(request(body, TOKEN));
            assertThat(result.getStatusCode().value()).as(body).isEqualTo(400);
            assertThat(code(result.getBody())).isEqualTo("MALFORMED_REQUEST");
        }
        assertThat(journal.sessions(new GlassesAccess.Scope("test-venue", "test-staff"))).isEmpty();
    }

    @Test void aLongNoteIsTooLargeAndTheBatchSharesThePilotRateLimit() throws Exception {
        var longNote = controller.events(request(batch(List.of(Map.of("at", Instant.now().toString(), "type", "CLIENT_ERROR", "note", "x".repeat(201)))), TOKEN));
        assertThat(longNote.getStatusCode().value()).isEqualTo(413);
        for (int i = 0; i < 9; i++) controller.events(request("{}", TOKEN));
        var limited = controller.events(request(batch(List.of(event("STEP_DONE", null))), TOKEN));
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
