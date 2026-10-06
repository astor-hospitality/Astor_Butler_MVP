package museon_online.astor_butler.integration.saby;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Where a business lunch order leaves Butler for Saby. Like {@link SabyReservationProvider} it is switched off by default
 * and fails closed: until the official Saby contract for orders is known and implemented, it accepts nothing and calls nothing.
 */
@Component
@RequiredArgsConstructor
public class SabyLunchOrderProvider implements ExternalLunchOrderProvider {

    private final SabyReservationProperties properties;

    @Override
    public String providerId() {
        return SabyReservationProvider.PROVIDER_ID;
    }

    @Override
    public ExternalReservationStatus status() {
        List<String> missing = properties.missingConfiguration();
        return new ExternalReservationStatus(
                providerId(),
                properties.isEnabled(),
                properties.isEnabled() && missing.isEmpty(),
                missing,
                properties.isEnabled() ? "CONFIG_REQUIRED" : "DISABLED"
        );
    }

    @Override
    public Result submit(BusinessLunchOrder order, String idempotencyKey) {
        if (!status().configured()) {
            return new Result(false, providerId(), "PROVIDER_NOT_CONFIGURED", "", "Saby is not configured; the order was not sent.");
        }
        return new Result(
                false,
                providerId(),
                "PROVIDER_CONTRACT_NOT_IMPLEMENTED",
                "",
                "Saby order write is blocked until the official API contract is implemented and approved."
        );
    }
}
