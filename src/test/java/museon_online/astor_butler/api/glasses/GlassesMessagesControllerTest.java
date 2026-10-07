package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.ModelGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlassesMessagesControllerTest {
    private static final String TOKEN = "unit-test-only-not-a-real-credential";
    private static final String PASSWORD = "unit-report-password-not-real";
    private final ObjectMapper mapper = new ObjectMapper();
    private final GlassesAccess access = access();
    private final GlassesAssistService service = new GlassesAssistService(mock(ModelGateway.class), true, 1000);
    private final GlassesMessagesController controller =
            new GlassesMessagesController(access, service, new GlassesReportAuth(access, PASSWORD), mapper);

    @AfterEach void close() { service.close(); }

    private GlassesAccess access() {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
            return new GlassesAccess(hash, "test-venue", "test-staff", Instant.now().plusSeconds(60).toString());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private MockHttpServletRequest post(String json, String password) {
        var request = new MockHttpServletRequest("POST", "/api/glasses/messages");
        if (password != null) {
            request.addHeader("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(("astor:" + password).getBytes(StandardCharsets.UTF_8)));
        }
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletRequest get(String bearer) {
        var request = new MockHttpServletRequest("GET", "/api/glasses/messages");
        if (bearer != null) request.addHeader("Authorization", "Bearer " + bearer);
        return request;
    }

    private String code(Object body) {
        return ((GlassesController.ErrorResponse) body).error().get("code");
    }

    @Test void theRestaurantQueuesWithThePasswordAndThePhoneReadsWithItsBearer() throws Exception {
        var queued = controller.queue(post(mapper.writeValueAsString(Map.of("text", "  Стол пять просит счёт.  ")), PASSWORD));
        assertThat(queued.getStatusCode().value()).isEqualTo(200);
        var receipt = (GlassesMessagesController.Queued) queued.getBody();
        assertThat(receipt.pending()).isEqualTo(1);
        assertThat(java.util.UUID.fromString(receipt.id())).isNotNull();

        var read = controller.messages(get(TOKEN));
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        var pending = (GlassesMessagesController.Pending) read.getBody();
        assertThat(pending.messages()).hasSize(1);
        assertThat(pending.messages().get(0).text()).isEqualTo("Стол пять просит счёт.");
        assertThat(pending.messages().get(0).id()).isEqualTo(receipt.id());
        // Reading does not consume: the phone decides when it is spoken, and repeats are its own problem.
        assertThat(((GlassesMessagesController.Pending) controller.messages(get(TOKEN)).getBody()).messages()).hasSize(1);
    }

    @Test void thePhoneCannotQueueAndTheRestaurantCannotRead() throws Exception {
        String body = mapper.writeValueAsString(Map.of("text", "Текст"));
        var asPhone = controller.queue(post(body, null));
        assertThat(asPhone.getStatusCode().value()).isEqualTo(401);
        assertThat(asPhone.getHeaders().getFirst("WWW-Authenticate")).startsWith("Basic");
        assertThat(controller.queue(post(body, "wrong-password-value")).getStatusCode().value()).isEqualTo(401);

        var asRestaurant = controller.messages(get(null));
        assertThat(asRestaurant.getStatusCode().value()).isEqualTo(401);
        assertThat(asRestaurant.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(((GlassesMessagesController.Pending) controller.messages(get(TOKEN)).getBody()).messages()).isEmpty();
    }

    @Test void withoutAServerPasswordNothingCanBeQueued() throws Exception {
        var unconfigured = new GlassesMessagesController(access, service, new GlassesReportAuth(access, ""), mapper);
        var result = unconfigured.queue(post(mapper.writeValueAsString(Map.of("text", "Текст")), ""));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(code(result.getBody())).isEqualTo("REPORT_UNAVAILABLE");
    }

    @Test void refusesEmptyOversizedAndDecoratedPayloadsAndAFullOutbox() throws Exception {
        for (String body : new String[]{"{}", "{\"text\":\"\"}", "{\"text\":\"  \"}",
                mapper.writeValueAsString(Map.of("text", "Текст", "staffId", "7")),
                mapper.writeValueAsString(Map.of("text", 7))}) {
            var result = controller.queue(post(body, PASSWORD));
            assertThat(result.getStatusCode().value()).as(body).isEqualTo(400);
            assertThat(code(result.getBody())).isEqualTo("MALFORMED_REQUEST");
        }
        var tooLong = controller.queue(post(mapper.writeValueAsString(Map.of("text", "ё".repeat(GlassesMessagesController.TEXT_LIMIT + 1))), PASSWORD));
        assertThat(tooLong.getStatusCode().value()).isEqualTo(413);

        for (int i = 0; i < GlassesMessagesController.PENDING_LIMIT; i++) {
            assertThat(controller.queue(post(mapper.writeValueAsString(Map.of("text", "Сообщение " + i)), PASSWORD))
                    .getStatusCode().value()).isEqualTo(200);
        }
        var full = controller.queue(post(mapper.writeValueAsString(Map.of("text", "Ещё одно")), PASSWORD));
        assertThat(full.getStatusCode().value()).isEqualTo(429);
        assertThat(code(full.getBody())).isEqualTo("OUTBOX_FULL");
        assertThat(((GlassesMessagesController.Pending) controller.messages(get(TOKEN)).getBody()).messages())
                .hasSize(GlassesMessagesController.PENDING_LIMIT);
    }

    @Test void messagesBelongToOneScope() throws Exception {
        controller.queue(post(mapper.writeValueAsString(Map.of("text", "Для этой смены")), PASSWORD));
        assertThat(controller.pending(new GlassesAccess.Scope("other-venue", "test-staff"))).isEmpty();
        assertThat(controller.pending(new GlassesAccess.Scope("test-venue", "other-staff"))).isEmpty();
        assertThat(controller.pending(new GlassesAccess.Scope("test-venue", "test-staff"))).hasSize(1);
    }
}
