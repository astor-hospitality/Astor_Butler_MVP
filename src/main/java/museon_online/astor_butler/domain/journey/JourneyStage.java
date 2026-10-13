package museon_online.astor_butler.domain.journey;

/** Stages of a guest visit (spec §4); the last three are exits. A visit only moves forward or into an exit. */
public enum JourneyStage {
    REQUESTED(1), CONFIRMED(2), VISIT_DAY(3), SEATED(4), BILL(5), AFTER(6), RETURNED(7),
    CANCELLED(100), NO_SHOW(100);

    private final int order;

    JourneyStage(int order) {
        this.order = order;
    }

    public int order() {
        return order;
    }

    public boolean isExit() {
        return order >= 100;
    }
}
