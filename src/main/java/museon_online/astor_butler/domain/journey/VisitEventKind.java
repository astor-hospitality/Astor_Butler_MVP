package museon_online.astor_butler.domain.journey;

/** What happened in a visit: the axis it lights, the stage it moves the visit to (or null), and whether it happens once. */
public enum VisitEventKind {
    REQUESTED(JourneyAxis.TIME, JourneyStage.REQUESTED, true),
    RECOGNIZED(JourneyAxis.IDENTITY, null, true),
    REPEAT_OFFERED(JourneyAxis.PERSONALIZATION, null, true),
    REPEAT_ACCEPTED(JourneyAxis.PERSONALIZATION, null, true),
    CONFIRMED(JourneyAxis.TIME, JourneyStage.CONFIRMED, true),
    MORNING_CONFIRMED(JourneyAxis.TIME, JourneyStage.VISIT_DAY, true),
    MOVED(JourneyAxis.TIME, null, false),
    SEATED(JourneyAxis.IDENTITY, JourneyStage.SEATED, true),
    DISH_QUESTION(JourneyAxis.INFO, null, false),
    VENUE_QUESTION(JourneyAxis.INFO, null, false),
    TABLE_REQUEST(JourneyAxis.IDENTITY, null, false),
    CHOICE_THEATRE(JourneyAxis.PLAY, null, false),
    BILL_REQUESTED(JourneyAxis.GRATITUDE, JourneyStage.BILL, true),
    REVIEW(JourneyAxis.GRATITUDE, null, true),
    TIP(JourneyAxis.GRATITUDE, null, true),
    GOOD_DEED(JourneyAxis.SOCIAL, null, true),
    AFTER_SENT(JourneyAxis.GRATITUDE, JourneyStage.AFTER, true),
    INVITED(JourneyAxis.PERSONALIZATION, null, true),
    RETURNED(JourneyAxis.IDENTITY, JourneyStage.RETURNED, true),
    CANCELLED(JourneyAxis.SAFETY, JourneyStage.CANCELLED, true),
    NO_SHOW(JourneyAxis.NONE, JourneyStage.NO_SHOW, true),
    OPTED_OUT(JourneyAxis.SAFETY, JourneyStage.OPTED_OUT, true);

    private final JourneyAxis axis;
    private final JourneyStage stage;
    private final boolean oneShot;

    VisitEventKind(JourneyAxis axis, JourneyStage stage, boolean oneShot) {
        this.axis = axis;
        this.stage = stage;
        this.oneShot = oneShot;
    }

    public JourneyAxis axis() {
        return axis;
    }

    public JourneyStage stage() {
        return stage;
    }

    public boolean oneShot() {
        return oneShot;
    }
}
