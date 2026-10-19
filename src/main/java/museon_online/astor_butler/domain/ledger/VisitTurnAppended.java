package museon_online.astor_butler.domain.ledger;

/** Published after a turn is written to (or a session linked into) a visit; {@code visitId} is null for a bare session. */
public record VisitTurnAppended(Long visitId) {
}
