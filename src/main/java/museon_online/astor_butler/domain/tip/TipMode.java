package museon_online.astor_butler.domain.tip;

/** What happens after the guest confirms a tip. Butler never takes the money in either. */
public enum TipMode {
    /** Semi-automatic: a card goes to the waiters, who come with a QR or a terminal and mark it received. */
    STAFF_CARD,
    /** Automatic: the guest gets the recipient's SBP link; without one, the waiters get the card. */
    SBP_LINK
}
