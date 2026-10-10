package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import museon_online.astor_butler.api.glasses.tasks.StaffAssignees;
import museon_online.astor_butler.api.glasses.tasks.StaffPortalService;
import museon_online.astor_butler.api.glasses.tasks.StaffScope;
import museon_online.astor_butler.api.glasses.tasks.StaffTask;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskFailure;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskService;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where a task given by voice through the glasses becomes a staff task: the glasses runtime sends the
 * draft it made of the words, Butler decides who may give tasks, who is meant and who is on shift,
 * creates the task through the same rules as the staff portal, and tells the team in the system chat.
 *
 * Server to server only, behind the same shared token as the transcript relay. The wearer's role is
 * not in the request: it comes from configuration (`astor.staff.glasses-role`, MANAGER for the pilot),
 * and the wearer must still be an active member of the venue under that role. The request id is the
 * event id: a repeated request answers with the task it already created and notifies nobody twice.
 *
 * Tasks are off unless `astor.staff.tasks-enabled` (or the portal) is on; then this answers 503.
 */
@RestController
public class GlassesStaffTaskController {
    private static final Logger log = LoggerFactory.getLogger(GlassesStaffTaskController.class);
    static final int BODY_LIMIT = 16 * 1024;
    static final String SOURCE = "Astor Glass";
    /** Stored when no table was spoken: the rules require a table code, the words may not have one. */
    static final String NO_TABLE = "-";
    private static final Set<String> FIELDS = Set.of("requestId", "tenant", "staff", "draft");
    private static final Set<String> DRAFT_FIELDS = Set.of("assignee", "tableCode", "title", "instruction", "priority");
    private final ObjectProvider<StaffPortalService> portal;
    private final TelegramSystemNotifier notifier;
    private final String token;
    private final StaffScope.Role role;
    private final ObjectMapper mapper;

    public GlassesStaffTaskController(ObjectProvider<StaffPortalService> portal, TelegramSystemNotifier notifier,
                                      @Value("${astor.glasses.relay-token:}") String token,
                                      @Value("${astor.staff.glasses-role:MANAGER}") String role,
                                      ObjectMapper mapper) {
        this.portal = portal;
        this.notifier = notifier;
        this.token = token;
        this.role = role(role);
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static StaffScope.Role role(String value) {
        try {
            StaffScope.Role role = StaffScope.Role.valueOf((value == null ? "" : value).strip().toUpperCase(Locale.ROOT));
            if (role == StaffScope.Role.WAITER) throw new IllegalArgumentException("A waiter may not give tasks");
            return role;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("astor.staff.glasses-role must be MANAGER or HOSTESS", e);
        }
    }

    /** The task as the glasses repeat it; {@code assignee} is a display name and null when it stayed with the wearer. */
    public record Created(String taskId, String title, String assignee, String assigneeStaffId, boolean self,
                          String instruction, String tableCode, String priority, String status) { }

    @PostMapping("/api/internal/glasses/staff-tasks")
    public ResponseEntity<?> create(HttpServletRequest request) {
        try {
            authorize(request);
            StaffPortalService service = portal.getIfAvailable();
            if (service == null) throw failure(503, "TASKS_DISABLED");
            if (request.getContentLengthLong() > BODY_LIMIT) throw failure(413, "PAYLOAD_TOO_LARGE");
            if (request.getContentType() == null
                    || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw failure(400, "MALFORMED_REQUEST");
            }
            byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (bytes.length > BODY_LIMIT) throw failure(413, "PAYLOAD_TOO_LARGE");
            JsonNode body = mapper.readTree(bytes);
            if (body == null || !body.isObject()) throw failure(400, "MALFORMED_REQUEST");
            only(body, FIELDS);
            String requestId = uuid(text(body, "requestId"));
            String tenant = required(text(body, "tenant"), 80);
            String staff = required(text(body, "staff"), 128);
            JsonNode draft = body.get("draft");
            if (draft == null || !draft.isObject()) throw failure(400, "MALFORMED_REQUEST");
            only(draft, DRAFT_FIELDS);
            String title = cut(required(text(draft, "title"), 1000), 120);
            String instruction = cut(text(draft, "instruction"), 1000);
            String tableCode = cut(text(draft, "tableCode"), 32);
            String spokenAssignee = cut(text(draft, "assignee"), 120);
            StaffTask.Priority priority = priority(text(draft, "priority"));

            List<StaffPortalService.Member> members = service.members(tenant);
            // A repeat of the same request: the task it already made, and no second card in the chat.
            Optional<StaffTask> replayed = service.replay(tenant, requestId);
            if (replayed.isPresent()) return ok(created(replayed.get(), staff, members));

            StaffPortalService.Member assignee = StaffAssignees.resolve(members, spokenAssignee);
            String assigneeId = assignee == null ? staff : assignee.staffId();
            StaffScope scope = new StaffScope(tenant, staff, role);
            StaffTask task = service.create(scope, requestId, new StaffTaskService.Draft(SOURCE,
                    tableCode == null ? NO_TABLE : tableCode, title, instruction == null ? title : instruction,
                    List.of(new StaffTaskService.StageDraft("done", "Выполнить", false)), priority, null, assigneeId));
            Created created = created(task, staff, members);
            boolean chat = notifier.sendGlassesTask(name(members, staff), created.title(), created.instruction(),
                    created.tableCode(), name(members, created.assigneeStaffId()));
            if (!chat) log.debug("Glasses task card not sent to the system chat");
            return ok(created);
        } catch (Failure failure) {
            return error(failure.status, failure.code);
        } catch (StaffTaskFailure failure) {
            // The rules' own refusal: an unregistered wearer, an assignee off shift, a conflicting event.
            log.warn("Glasses staff task refused: {}", failure.code);
            return error(failure.status, failure.code);
        } catch (Exception e) {
            return error(400, "MALFORMED_REQUEST");
        }
    }

    private Created created(StaffTask task, String wearer, List<StaffPortalService.Member> members) {
        boolean self = task.assigneeStaffId().equals(wearer);
        String table = NO_TABLE.equals(task.tableCode()) ? null : task.tableCode();
        return new Created(task.taskId(), task.title(), self ? null : name(members, task.assigneeStaffId()),
                task.assigneeStaffId(), self, task.instruction(), table, task.priority().name(), task.status().name());
    }

    private static String name(List<StaffPortalService.Member> members, String staffId) {
        return members.stream().filter(member -> staffId.equals(member.staffId())).map(StaffPortalService.Member::displayName)
                .filter(name -> name != null && !name.isBlank()).findFirst().orElse(staffId);
    }

    private void authorize(HttpServletRequest request) {
        if (token == null || token.length() < 16) throw failure(503, "RELAY_UNAVAILABLE");
        String presented = request.getHeader("X-Astor-Relay-Token");
        if (presented == null || presented.length() > 512) throw failure(401, "UNAUTHORIZED");
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            throw failure(401, "UNAUTHORIZED");
        }
    }

    private static StaffTask.Priority priority(String value) {
        if (value == null) return StaffTask.Priority.NORMAL;
        try {
            return StaffTask.Priority.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw failure(400, "MALFORMED_REQUEST");
        }
    }

    private void only(JsonNode node, Set<String> fields) {
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw failure(400, "MALFORMED_REQUEST");
    }

    private String text(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.asText();
        if (!value.isTextual()) throw failure(400, "MALFORMED_REQUEST");
        String text = value.textValue().strip();
        return text.isEmpty() ? null : text;
    }

    private static String required(String value, int limit) {
        if (value == null || value.length() > limit) throw failure(400, "MALFORMED_REQUEST");
        return value;
    }

    private static String cut(String text, int limit) {
        if (text == null) return null;
        if (text.length() <= limit) return text;
        String cut = text.substring(0, limit);
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) cut = cut.substring(0, cut.length() - 1);
        return cut.strip();
    }

    private String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw failure(400, "MALFORMED_REQUEST");
        return UUID.fromString(value).toString();
    }

    private ResponseEntity<?> ok(Created created) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(created);
    }

    private ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", Map.of("code", code)));
    }

    private static Failure failure(int status, String code) { return new Failure(status, code); }

    private static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        private Failure(int status, String code) { super(code); this.status = status; this.code = code; }
    }
}
