package museon_online.astor_butler.domain.billing;

import java.util.Locale;

/** When the guest is asked how the visit went. */
public enum ReviewMoment {
    /** As before: when the venue closes the order, or by the clock after the visit ends. */
    AFTER_CLOSE,
    /**
     * At the bill, while the guest is still at the table: with the payment link in the automatic path, by the hostess
     * «Счёт» button in the semi-automatic one. The close and the clock stay as the safety net, and the question is
     * still asked once per visit.
     */
    ON_BILL;

    /** Reads the env spelling ({@code on-bill}, {@code after-close}); anything unknown is the old behaviour. */
    public static ReviewMoment parse(String value) {
        if (value == null || value.isBlank()) {
            return AFTER_CLOSE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ReviewMoment moment : values()) {
            if (moment.name().equals(normalized)) {
                return moment;
            }
        }
        return AFTER_CLOSE;
    }
}
