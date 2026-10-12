package museon_online.astor_butler.domain.tips;

/**
 * A row of the staff directory as the tips side sees it.
 *
 * @param tipLink the waiter's own link for tips (an SBP link to a private person), null until the manager sets one
 * @param tipNote a short note for the manager (which bank, which path), never shown to the guest
 */
public record TipStaffMember(
        String staffId,
        String displayName,
        String role,
        boolean active,
        boolean shiftOpen,
        String tipLink,
        String tipNote
) {

    public boolean hasTipLink() {
        return tipLink != null && !tipLink.isBlank();
    }
}
