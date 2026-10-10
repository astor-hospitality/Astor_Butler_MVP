package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * Hands a spoken task to Butler, which owns staff, shifts and the task store. The glasses runtime keeps
 * no task of its own: it only asks Butler to create one and repeats Butler's receipt to the wearer.
 *
 * Synchronous on purpose, unlike the transcript relay: the wearer is waiting to hear whether the task
 * was recorded, and a confirmation that Butler did not give would be a lie. The same shared token as
 * the transcript relay guards the call, so one server-side secret covers both directions of this link.
 *
 * Failures are mapped to a single refusal the wearer can act on: say it again. No URL, token or spoken
 * text reaches the log.
 */
@Component
public class GlassesStaffTaskRelay {
    private static final Logger log = LoggerFactory.getLogger(GlassesStaffTaskRelay.class);
    static final String FAILURE_CODE = "TASK_UNAVAILABLE";
    static final String FAILURE_MESSAGE = "Не смог записать поручение, повторите";
    private final boolean enabled;
    private final URI upstream;
    private final String token;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GlassesStaffTaskRelay(
            @Value("${astor.glasses.tasks-enabled:false}") boolean enabled,
            @Value("${astor.glasses.staff-tasks-url:http://aeris-astor-butler-bot:8089/api/internal/glasses/staff-tasks}") String upstream,
            @Value("${astor.glasses.relay-token:}") String token) {
        this.enabled = enabled;
        this.upstream = URI.create(upstream);
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    GlassesStaffTaskRelay(HttpClient client, boolean enabled, String token) {
        this.enabled = enabled;
        this.upstream = URI.create("http://butler.invalid/api/internal/glasses/staff-tasks");
        this.token = token;
        this.client = client;
    }

    public static GlassesStaffTaskRelay disabled() {
        return new GlassesStaffTaskRelay(HttpClient.newHttpClient(), false, "");
    }

    boolean configured() { return enabled && token.length() >= 16; }

    /** Asks Butler to create the task; the request id is the event id, so a retry creates nothing twice. */
    GlassesVoiceTasks.Receipt create(GlassesAccess.Scope scope, String requestId, GlassesVoiceTasks.Draft draft) {
        if (!configured()) throw refused();
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("requestId", requestId);
            body.put("tenant", scope.tenant());
            body.put("staff", scope.staff());
            var fields = new LinkedHashMap<String, Object>();
            fields.put("assignee", draft.assignee());
            fields.put("tableCode", draft.tableCode());
            fields.put("title", draft.title());
            fields.put("instruction", draft.instruction());
            fields.put("priority", draft.priority());
            body.put("draft", fields);
            var request = HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("X-Astor-Relay-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Glasses staff task refused by Butler: HTTP {}", response.statusCode());
                throw refused();
            }
            return receipt(mapper.readTree(response.body()));
        } catch (GlassesFailure failure) {
            throw failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refused();
        } catch (Exception e) {
            log.warn("Glasses staff task relay failed: {}", e.getClass().getSimpleName());
            throw refused();
        }
    }

    private GlassesVoiceTasks.Receipt receipt(JsonNode json) {
        if (json == null || !json.isObject() || text(json, "taskId") == null) throw refused();
        return new GlassesVoiceTasks.Receipt(text(json, "taskId"), text(json, "title"), text(json, "assignee"),
                text(json, "assigneeStaffId"), json.path("self").asBoolean(false), text(json, "instruction"),
                text(json, "tableCode"), text(json, "priority"), text(json, "status"));
    }

    private static String text(JsonNode json, String name) {
        JsonNode value = json.get(name);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static GlassesFailure refused() {
        return new GlassesFailure(503, FAILURE_CODE, FAILURE_MESSAGE);
    }
}
