package com.astor.glasses.core;

/**
 * Decides when putting the glasses on deserves a greeting. Reconnection is not a wear event; duplicates,
 * a 30-second cooldown and a 10-second expiry keep the greeting from repeating or arriving late.
 * Status values follow the glasses SDK: unknown -1, off 0, worn 1..3. Times are milliseconds.
 */
public final class WearGreeting {
    private static final long COOLDOWN_MS = 30_000, PENDING_MS = 10_000;

    private boolean worn;
    private Long pendingUntil, lastGreeting;

    public void observeStatus(int status, long now) {
        if (status < 0 || status > 3) {
            pendingUntil = null;
            worn = false;
            return;
        }
        boolean nowWorn = status > 0;
        if (nowWorn && !worn && (lastGreeting == null || now - lastGreeting >= COOLDOWN_MS)) pendingUntil = now + PENDING_MS;
        if (!nowWorn) pendingUntil = null;
        worn = nowWorn;
    }

    /** True once per wear event, and only while the phone is free to speak. */
    public boolean consume(long now, boolean allowed) {
        if (pendingUntil == null) return false;
        if (now >= pendingUntil) {
            pendingUntil = null;
            return false;
        }
        if (!allowed || !worn) return false;
        pendingUntil = null;
        lastGreeting = now;
        return true;
    }
}
