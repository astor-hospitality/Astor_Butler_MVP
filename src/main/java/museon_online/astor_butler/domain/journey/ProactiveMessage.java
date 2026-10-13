package museon_online.astor_butler.domain.journey;

/**
 * The useful messages the bot may send first (spec §4 whitelist). Answers to the guest's own action — a confirmed request
 * (also after a new time), a payment link, «staff is coming» — always go; the rest once per visit and never after opt-out.
 */
public enum ProactiveMessage {
    REQUEST_RECEIVED(true), CONFIRMED(true), MORNING(false), SEATED_HINT(false), STAFF_COMING(true), BILL_READY(true),
    AFTER(false), INVITE(false);

    private final boolean answer;

    ProactiveMessage(boolean answer) {
        this.answer = answer;
    }

    /** True when the message answers something the guest did, so the filter never holds it back. */
    public boolean answer() {
        return answer;
    }
}
