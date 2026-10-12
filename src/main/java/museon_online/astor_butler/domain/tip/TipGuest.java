package museon_online.astor_butler.domain.tip;

/** Who leaves the tip, as the waiters' card names them. */
public record TipGuest(Long chatId, String name, String username) {
}
