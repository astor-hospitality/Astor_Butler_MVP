package museon_online.astor_butler.domain.tip;

/**
 * Where the guest sits, for the waiters' card: the table of today's reservation and its number.
 *
 * @param table the table's name or code; null when the reservation has no table yet
 */
public record TipVisit(String table, Long reservationId) {
}
