package museon_online.astor_butler.api.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.OptionalLong;

/** Internal: the Concierge writes its turns into the visit ledger (guarded by the internal token). */
@RestController
@RequestMapping("/api/ledger/v1")
@Tag(name = "Visit ledger", description = "Internal: turns of a guest's visit")
@RequiredArgsConstructor
public class LedgerController {

    private static final int MAX_SESSION_ID = 64;

    private final LedgerWriter ledger;

    @PostMapping("/turns")
    @Operation(summary = "Append a visit turn")
    public ResponseEntity<Map<String, Object>> appendTurn(@RequestBody VisitTurnDraft draft) {
        if (draft == null || draft.side() == null || draft.channel() == null || draft.kind() == null
                || (draft.sessionId() != null && draft.sessionId().length() > MAX_SESSION_ID)) {
            return ResponseEntity.badRequest().body(Map.of("error", "side, channel and kind are required; sessionId up to 64"));
        }
        if (!ledger.enabled()) {
            return unavailable();
        }
        OptionalLong id = ledger.append(draft);
        return id.isPresent() ? ResponseEntity.ok(Map.of("id", id.getAsLong())) : unavailable();
    }

    @PostMapping("/sessions/{sessionId}/link")
    @Operation(summary = "Link a Concierge session's turns to a visit")
    public ResponseEntity<Map<String, Object>> linkSession(@PathVariable("sessionId") String sessionId, @RequestBody LinkRequest request) {
        if (request == null || request.visitId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "visitId is required"));
        }
        if (!ledger.enabled()) {
            return unavailable();
        }
        return ResponseEntity.ok(Map.of("linked", ledger.linkSession(sessionId, request.visitId())));
    }

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "ledger is off or unavailable"));
    }

    public record LinkRequest(Long visitId) {
    }
}
