package museon_online.astor_butler.api.negotiation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import museon_online.astor_butler.agent.ButlerAgent;
import museon_online.astor_butler.agent.NegotiationReply;
import museon_online.astor_butler.agent.NegotiationTurn;
import museon_online.astor_butler.domain.calllog.CallLogContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal: the Concierge talks to the venue (guarded by the internal token). A turn without a session is a 400; an
 * agent that cannot answer is still a 200 with {@code fallback = true}.
 *
 * <p>Safe retries: send a {@code turnId} and repeat it when retrying the same turn (e.g. after a lost response). The
 * tool calls of the turn are keyed {@code <sessionId>:<turnId>:<callNo>}, so a booking or an order made by the first
 * attempt is replayed, not made again. Without {@code turnId} the key uses the session's own turn counter, which only
 * protects retries of turns that failed. {@code guestConsent} must be {@code true} for the Concierge's message to keep
 * its text in the ledger; otherwise the row is written hidden, without text.
 */
@RestController
@RequestMapping("/api/negotiation/v1")
@Tag(name = "Negotiation", description = "Internal: the Concierge negotiates with the Butler's agent")
public class NegotiationController {

    private final ButlerAgent agent;

    public NegotiationController(ButlerAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/turn")
    @Operation(summary = "One negotiation turn: the guest's position in, the venue's answer and offer out")
    public ResponseEntity<NegotiationReply> turn(@RequestBody(required = false) NegotiationTurn turn) {
        if (turn == null || turn.sessionId() == null || turn.sessionId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // The call log counts the agent's model calls for this session (pseudonymized there), keyed by the turn.
        String correlation = turn.turnId() == null || turn.turnId().isBlank() ? turn.sessionId().trim() : turn.turnId().trim();
        try (CallLogContext.Scope ignored = CallLogContext.open("concierge", turn.sessionId().trim(), correlation)) {
            return ResponseEntity.ok(agent.negotiate(turn));
        }
    }
}
