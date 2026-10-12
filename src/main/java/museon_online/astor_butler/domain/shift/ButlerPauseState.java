package museon_online.astor_butler.domain.shift;

import java.time.Instant;

/**
 * The shift manager's stop button (#134). While {@code paused} is on, guest scenarios that would write something
 * (a booking, a change, a lunch, a bill) are held for the team and the timers do nothing. Who pressed it and when
 * travel with the state so the staff chat can tell.
 */
public record ButlerPauseState(boolean paused, String switchedBy, Instant switchedAt) {

    public static ButlerPauseState running() {
        return new ButlerPauseState(false, null, null);
    }
}
