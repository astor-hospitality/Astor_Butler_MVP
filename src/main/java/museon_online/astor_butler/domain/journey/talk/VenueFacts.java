package museon_online.astor_butler.domain.journey.talk;

/**
 * Facts about the restaurant the butler may tell a guest (spec 2026-10-13 §3.3). Only facts confirmed by the venue;
 * a null fact is answered with «уточню у команды».
 */
public record VenueFacts(String parking, String kids, String wifi, String winePairing, String events) {

    public static VenueFacts none() {
        return new VenueFacts(null, null, null, null, null);
    }
}
