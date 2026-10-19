package museon_online.astor_butler.domain.ledger;

import java.time.Instant;
import java.util.Map;

/** One written turn of a visit. */
public record VisitTurn(long id, Long visitId, String sessionId, Instant at, TurnSide side, TurnChannel channel,
                        TurnKind kind, String text, Map<String, Object> payload, String goal) {
}
