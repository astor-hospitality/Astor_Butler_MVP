package museon_online.astor_butler.domain.tips;

/**
 * Where a guest's tip goes.
 *
 * @param name     the recipient as the guest sees it; null when the venue has no recipient at all
 * @param link     the link the guest can pay by; null when there is none
 * @param personal true when the recipient is the waiter of the guest's table with their own link, false for the
 *                 venue's default recipient
 * @param staffId  the directory id of the waiter; null for the default recipient
 */
public record TipRecipient(String name, String link, boolean personal, String staffId) {

    public static TipRecipient personal(TipStaffMember waiter) {
        return new TipRecipient(waiter.displayName(), waiter.tipLink().trim(), true, waiter.staffId());
    }

    public static TipRecipient venue(String name, String link) {
        return new TipRecipient(name, link, false, null);
    }

    public static TipRecipient none() {
        return new TipRecipient(null, null, false, null);
    }
}
