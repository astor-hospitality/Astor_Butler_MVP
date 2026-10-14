package museon_online.astor_butler.api.negotiation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import museon_online.astor_butler.agent.ButlerAgent;
import museon_online.astor_butler.agent.NegotiationReply;
import museon_online.astor_butler.agent.NegotiationTurn;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal: the Concierge talks to the venue (guarded by the internal token). A turn without a session is a 400; an
 * agent that cannot answer is still a 200 with {@code fallback = true}.
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
        return ResponseEntity.ok(agent.negotiate(turn));
    }
}
