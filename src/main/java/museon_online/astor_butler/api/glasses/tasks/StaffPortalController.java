package museon_online.astor_butler.api.glasses.tasks;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(name="astor.staff.enabled", havingValue="true")
public class StaffPortalController {
    private final StaffPortalService portal;
    private final StaffScopes scopes = new StaffScopes("astor-api");
    public StaffPortalController(StaffPortalService portal) { this.portal = portal; }
    private StaffScope scope(Jwt token) { return scopes.from(token == null ? null : token.getClaims()); }

    @GetMapping("/api/admin/staff-tasks/dashboard")
    public StaffPortalService.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) { return portal.dashboard(scope(jwt)); }
    @GetMapping("/api/admin/staff-tasks/{task}/history")
    public List<StaffPortalService.Audit> history(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task) {
        return portal.history(scope(jwt), task);
    }
    @GetMapping("/api/staff/tasks")
    public Map<String, Object> tasks(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("items", portal.snapshot(scope(jwt)), "fullSnapshot", true);
    }
    @PostMapping("/api/admin/staff-tasks")
    public StaffTask create(@AuthenticationPrincipal Jwt jwt, @RequestBody Create request) {
        return portal.create(scope(jwt), request.eventId(), request.task());
    }
    @PostMapping("/api/admin/staff-tasks/{task}/reassign")
    public StaffTask reassign(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task, @RequestBody Mutation request) {
        return portal.reassign(scope(jwt), task, request.eventId(), request.expectedVersion(), request.staffId());
    }
    @PostMapping("/api/admin/staff-tasks/{task}/cancel")
    public StaffTask cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task, @RequestBody Mutation request) {
        return portal.cancel(scope(jwt), task, request.eventId(), request.expectedVersion());
    }
    @PostMapping("/api/admin/staff-tasks/{task}/resolve-help")
    public StaffTask help(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task, @RequestBody Mutation request) {
        return portal.resolveHelp(scope(jwt), task, request.eventId(), request.expectedVersion());
    }
    @PutMapping("/api/admin/staff/members/{staff}")
    public ResponseEntity<Void> member(@AuthenticationPrincipal Jwt jwt, @PathVariable("staff") String staff, @RequestBody Member request) {
        portal.member(scope(jwt), staff, request.displayName(), request.role(), request.active());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/admin/staff/members/{staff}/shift")
    public ResponseEntity<Void> shift(@AuthenticationPrincipal Jwt jwt, @PathVariable("staff") String staff, @RequestBody Shift request) {
        portal.shift(scope(jwt), staff, request.open(), request.deviceId());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/staff/tasks/{task}/commands")
    public StaffTask command(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task, @RequestBody Command request) {
        return portal.command(scope(jwt), task, request.eventId(), request.type(), request.expectedVersion(), request.stageCode());
    }
    @PostMapping("/api/staff/tasks/{task}/delivery")
    public StaffTask delivery(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task, @RequestBody Delivery request) {
        return portal.delivery(scope(jwt), task, request.eventId(), request.kind());
    }
    /** A proof photo for one stage: the file is the body, eventId and stageCode are query parameters. */
    @PostMapping("/api/staff/tasks/{task}/evidence")
    public ResponseEntity<?> evidence(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task,
                                      HttpServletRequest request) throws IOException {
        StaffScope scope = scope(jwt);
        var limits = portal.evidenceLimits();
        if (limits.isEmpty()) {
            // Never increment an evidence counter without a real persisted photo and scoped retrieval.
            portal.snapshot(scope);
            return ResponseEntity.status(503).body(Map.of("error", Map.of("code", "EVIDENCE_UNAVAILABLE",
                    "message", "Photo storage is not connected yet")));
        }
        // Who may upload is settled before a single byte of the photo is read.
        portal.evidenceUpload(scope, task);
        StaffEvidenceUpload upload = StaffEvidenceUpload.read(request, limits.get());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(portal.evidence(scope, task,
                upload.eventId(), upload.stageCode(), upload.capturedAt(), upload.contentType(), upload.bytes()));
    }
    @GetMapping("/api/staff/tasks/{task}/evidence")
    public Map<String, Object> ownEvidence(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task) {
        return Map.of("items", portal.evidenceForAssignee(scope(jwt), task));
    }
    @GetMapping("/api/staff/tasks/{task}/evidence/{evidence}")
    public ResponseEntity<byte[]> ownPhoto(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task,
                                           @PathVariable("evidence") String evidence) {
        return photo(portal.evidenceFileForAssignee(scope(jwt), task, evidence));
    }
    @GetMapping("/api/admin/staff-tasks/{task}/evidence")
    public Map<String, Object> taskEvidence(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task) {
        return Map.of("items", portal.evidenceForManager(scope(jwt), task));
    }
    @GetMapping("/api/admin/staff-tasks/{task}/evidence/{evidence}")
    public ResponseEntity<byte[]> taskPhoto(@AuthenticationPrincipal Jwt jwt, @PathVariable("task") String task,
                                            @PathVariable("evidence") String evidence) {
        return photo(portal.evidenceFileForManager(scope(jwt), task, evidence));
    }
    /** The stored bytes under their recorded type; never cached, never guessed at by a browser. */
    private static ResponseEntity<byte[]> photo(StaffEvidence.File file) {
        String type = file.evidence().contentType();
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline; filename=\"" + file.evidence().evidenceId() + "."
                        + StaffEvidenceImages.extension(type) + "\"")
                .contentType(MediaType.parseMediaType(type)).body(file.bytes());
    }
    @ExceptionHandler(StaffTaskFailure.class)
    public ResponseEntity<?> failure(StaffTaskFailure failure) {
        return ResponseEntity.status(failure.status).header("Cache-Control", "no-store")
                .body(Map.of("error", Map.of("code", failure.code, "message", failure.getMessage())));
    }
    public record Create(String eventId, StaffTaskService.Draft task) { }
    public record Mutation(String eventId, long expectedVersion, String staffId) { }
    public record Member(String displayName, String role, boolean active) { }
    public record Shift(boolean open, String deviceId) { }
    public record Command(String eventId, StaffTaskService.Command type, long expectedVersion, String stageCode) { }
    public record Delivery(String eventId, StaffTaskService.Delivery kind) { }
}
