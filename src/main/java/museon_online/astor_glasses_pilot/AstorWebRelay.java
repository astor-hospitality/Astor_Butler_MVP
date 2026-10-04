package museon_online.astor_glasses_pilot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Public presentation transport: only anonymous WEB messages, never client-selected staff/FSM channels. */
@RestController
public class AstorWebRelay {
    private final URI upstream;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(4);

    public AstorWebRelay(@Value("${ASTOR_WEB_UPSTREAM:http://aeris-astor-butler-bot:8089/api/messages}") String upstream) {
        this.upstream = URI.create(upstream);
    }

    @PostMapping("/api/astor/messages")
    public ResponseEntity<?> process(HttpServletRequest request) {
        if (!slots.tryAcquire()) return error(429, "WEB_BUSY");
        try {
            if (request.getContentType() == null || !request.getContentType().split(";",2)[0].trim().equalsIgnoreCase("application/json")) {
                return error(400, "INVALID_WEB_MESSAGE");
            }
            byte[] input = request.getInputStream().readNBytes(16385);
            if (input.length > 16384) return error(413, "WEB_MESSAGE_TOO_LARGE");
            JsonNode body = mapper.readTree(input);
            if (!fields(body, Set.of("channel", "text", "payload")) || !"WEB".equals(body.path("channel").asText())) {
                return error(400, "INVALID_WEB_MESSAGE");
            }
            var text = body.path("text");
            var payload = body.path("payload");
            if (!text.isTextual() || text.asText().isBlank() || text.asText().codePointCount(0,text.asText().length()) > 4000
                    || !fields(payload, Set.of("sessionId", "site", "pageContext", "sentAt"))) return error(400, "INVALID_WEB_MESSAGE");
            var session = payload.path("sessionId");
            if (!session.isTextual() || !session.asText().matches("web-[a-z0-9]{1,32}-[a-z0-9]{1,32}")) return error(400, "INVALID_WEB_MESSAGE");
            // Rebuild all forwarded fields; never forward identity, tenant, chatId, consent, actions or arbitrary metadata.
            byte[] safe = mapper.writeValueAsBytes(Map.of("channel", "WEB", "text", text.asText(), "payload",
                    Map.of("sessionId", session.asText(), "site", "astor-butler-commercial",
                            "pageContext", "commercial_landing", "sentAt", Instant.now().toString())));
            var outgoing = HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(safe)).build();
            var response = client.send(outgoing, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
            byte[] bytes = response.body();
            if (response.statusCode() == 429) return error(429, "WEB_BUSY");
            if (response.statusCode() != 200 || bytes.length > 65536) return error(503, "WEB_UNAVAILABLE");
            JsonNode reply;
            try { reply = mapper.readTree(bytes).path("text"); }
            catch (Exception e) { return error(503, "WEB_UNAVAILABLE"); }
            if (!reply.isTextual() || reply.asText().isBlank()) return error(503, "WEB_UNAVAILABLE");
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("text", reply.asText().replace("команде C3AG", "команде Astor")));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return error(400, "INVALID_WEB_MESSAGE");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return error(503, "WEB_UNAVAILABLE");
        } finally { slots.release(); }
    }

    private boolean fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) return false;
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
        return true;
    }

    private ResponseEntity<?> error(int status, String code) {
        var result = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == 429) result.header("Retry-After", "60");
        return result.body(Map.of("error", Map.of("code", code)));
    }
}
