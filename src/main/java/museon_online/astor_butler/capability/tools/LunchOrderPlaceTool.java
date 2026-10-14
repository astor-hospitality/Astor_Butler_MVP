package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code lunch_order_place{reservationId, dishCodes, confirmedByGuest, setCode?}}: the business lunch the guest chose,
 * ordered into their reservation. Only after the guest said yes to the summary: {@code confirmedByGuest} must be the
 * boolean {@code true}. Dish codes come from {@code lunch_menu}; an unknown code is named back as a bad argument.
 */
@Component
public class LunchOrderPlaceTool implements CapabilityTool {

    private final BusinessLunchService lunch;
    private final BusinessLunchCatalog catalog;
    private final CapabilityProperties properties;
    private final ReservationAccess access;
    private final GuestJourneyProperties journeyProperties;

    public LunchOrderPlaceTool(BusinessLunchService lunch, BusinessLunchCatalog catalog, CapabilityProperties properties,
                               ReservationAccess access, GuestJourneyProperties journeyProperties) {
        this.lunch = lunch;
        this.catalog = catalog;
        this.properties = properties;
        this.access = access;
        this.journeyProperties = journeyProperties;
    }

    @Override
    public String name() {
        return "lunch_order_place";
    }

    @Override
    public String description() {
        return "Оформить бизнес-ланч к брони гостя. Вызывай только после того, как гость подтвердил состав заказа: "
                + "confirmedByGuest=true. Коды блюд — из lunch_menu. Возвращает номер заказа (orderId), сумму в рублях "
                + "(totalRub, если цены известны) и статус передачи в Saby (sabyStatus; MANUAL_ENTRY — внесут вручную).";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reservationId", Map.of("type", "integer", "description", "Номер брони гостя"));
        props.put("dishCodes", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Коды блюд из lunch_menu, по одному на порцию"));
        props.put("confirmedByGuest", Map.of("type", "boolean",
                "description", "true только если гость явно подтвердил заказ"));
        props.put("setCode", Map.of("type", "string", "description", "Код сета, если ланч подают сетами"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("reservationId", "dishCodes", "confirmedByGuest"));
    }

    @Override
    public boolean commitment() {
        return true;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        if (args == null || !Boolean.TRUE.equals(args.get("confirmedByGuest"))) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "not_confirmed_by_guest");
        }
        List<String> codes = dishCodes(args.get("dishCodes"));
        if (codes == null || codes.isEmpty()) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        ReservationAccess.Checked checked = access.check(ctx, args);
        if (!checked.allowed()) {
            return checked.error();
        }
        Optional<BusinessLunchOffer> found = catalog.find(properties.getVenueCode());
        if (found.isEmpty()) {
            return CapabilityResult.error(CapabilityResult.NOT_FOUND);
        }
        BusinessLunchOffer offer = found.get();
        List<String> unknown = codes.stream().filter(code -> offer.dish(code).isEmpty()).distinct().toList();
        if (!unknown.isEmpty()) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("unknownDishCodes", unknown);
            return new CapabilityResult(false, facts, CapabilityResult.BAD_ARGUMENT);
        }
        String setCode = CapabilityArguments.text(args, "setCode");
        if (!offer.aLaCarte() && (setCode == null || offer.set(setCode).isEmpty())) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        TableReservationOrder reservation = checked.order();
        if (!BusinessLunchService.holdsATable(reservation)) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "not_active");
        }
        if (reservation.requestedStartAt() == null) {
            return CapabilityResult.error(CapabilityResult.UNAVAILABLE);
        }
        ZonedDateTime start = reservation.requestedStartAt().atZone(ZoneId.of(journeyProperties.getZoneId()));
        Optional<BusinessLunchService.WindowIssue> issue = BusinessLunchService.lunchTimeIssue(offer, start.toLocalDate(),
                start.toLocalTime());
        if (issue.isPresent()) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, issue.get().name());
        }
        int guests = reservation.partySize() == null || reservation.partySize() < 1 ? 1 : reservation.partySize();
        BusinessLunchService.Placement placement;
        try {
            placement = lunch.placeFor(reservation, new BusinessLunchService.Request(
                    reservation.chatId(), reservation.telegramUserId(), offer, setCode, codes, guests, start.toLocalDate(),
                    start.toLocalTime(), reservation.guestName(), reservation.guestPhone(), null,
                    ctx.butlerAgent() ? "TELEGRAM" : CapabilityContext.CONCIERGE, null));
        } catch (IllegalStateException e) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "not_active");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderId", reservation.id());
        facts.put("totalRub", BusinessLunchService.totalRub(offer, setCode, codes, guests));
        facts.put("sabyStatus", placement.external() == null ? null : placement.external().status());
        return CapabilityResult.ok(facts);
    }

    private static List<String> dishCodes(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> codes = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null || item.toString().isBlank()) {
                return null;
            }
            codes.add(item.toString().trim());
        }
        return codes;
    }
}
