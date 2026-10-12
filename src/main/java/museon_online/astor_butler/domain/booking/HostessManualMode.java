package museon_online.astor_butler.domain.booking;

import java.time.Instant;

/**
 * The "overloaded" switch of the venue. While it is on, no hostess card confirms itself and an unanswered card goes to
 * the manager after the timeout instead. Who switched it, why and when travel with the state so the staff chat can tell.
 */
public record HostessManualMode(boolean manual, String reason, String switchedBy, Instant switchedAt) {

    public static HostessManualMode auto() {
        return new HostessManualMode(false, null, null, null);
    }
}
