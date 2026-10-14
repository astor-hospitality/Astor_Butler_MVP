package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.billing.VisitReviewService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code review_submit{reservationId, stars}}: the guest's stars for the visit, once per visit. */
@Component
public class ReviewSubmitTool implements CapabilityTool {

    private final VisitReviewService reviews;
    private final ReservationAccess access;

    public ReviewSubmitTool(VisitReviewService reviews, ReservationAccess access) {
        this.reviews = reviews;
        this.access = access;
    }

    @Override
    public String name() {
        return "review_submit";
    }

    @Override
    public String description() {
        return "Записать оценку визита гостем: звёзды от 1 до 5. Оценка принимается один раз за визит и только "
                + "при счёте, оформленном через Батлера. Возвращает accepted.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reservationId", Map.of("type", "integer", "description", "Номер брони гостя"));
        props.put("stars", Map.of("type", "integer", "minimum", 1, "maximum", 5, "description", "Оценка от 1 до 5"));
        return Map.of("type", "object", "properties", props, "required", List.of("reservationId", "stars"));
    }

    @Override
    public boolean commitment() {
        return true;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        Integer stars = CapabilityArguments.integer(args, "stars");
        if (stars == null || stars < 1 || stars > 5) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        ReservationAccess.Checked checked = access.check(ctx, args);
        if (!checked.allowed()) {
            return checked.error();
        }
        // The guest's words are not kept with the stars (visit_reviews has no text); the feedback scenario takes them.
        if (!reviews.submit(checked.order().id(), stars, null)) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "already_reviewed_or_no_bill");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("accepted", true);
        return CapabilityResult.ok(facts);
    }
}
