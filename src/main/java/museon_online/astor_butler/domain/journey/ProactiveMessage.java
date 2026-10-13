package museon_online.astor_butler.domain.journey;

/** The useful messages the bot may send first (spec §4 whitelist). STAFF_COMING repeats per request; the rest once per visit. */
public enum ProactiveMessage {
    REQUEST_RECEIVED, CONFIRMED, MORNING, SEATED_HINT, STAFF_COMING, BILL_READY, AFTER, INVITE
}
