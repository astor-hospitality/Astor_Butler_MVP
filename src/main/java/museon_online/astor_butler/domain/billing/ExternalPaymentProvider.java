package museon_online.astor_butler.domain.billing;

import java.util.Optional;

/**
 * The venue's own payment page for an order it holds, Saby for AERIS. Butler hands the link to the guest and never
 * sees the card: whether the guest paid comes back through the order's pay state, not through this port.
 */
public interface ExternalPaymentProvider {

    String providerId();

    /** Links are issued only while the venue's payment is switched on and configured. */
    boolean enabled();

    /**
     * The payment page for the venue's order, or empty when the venue has none for it.
     *
     * @throws RuntimeException when the venue's system could not be asked; the caller journals it and tries later
     */
    Optional<PaymentLink> paymentLink(String externalOrderId);

    /** @param amountMinor what the page asks for, in kopecks, when the venue says; null otherwise */
    record PaymentLink(String url, Long amountMinor) {
    }
}
