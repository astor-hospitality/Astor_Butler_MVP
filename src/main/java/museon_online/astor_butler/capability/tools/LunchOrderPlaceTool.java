package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code lunch_order_place{reservationId, dishCodes, confirmedByGuest, setCode?}}: the business lunch the guest chose,
 * ordered into their reservation. Only after the guest said yes to the summary: {@code confirmedByGuest} must be the
 * boolean {@code true}, and the guest's own words this turn must say yes ({@link GuestAgreement#yes}), else
 * {@code NOT_ALLOWED} with {@code reason = needs_guest_yes}. Dish codes come from {@code lunch_menu}; an unknown code
 * is named back as a bad argument. A lunch whose visit or lunch hours are already over by the venue's clock is
 * {@code NOT_ALLOWED} with {@code reason = already_passed}.
 *
 * <p>The guest's yes to the lunch summary also books the table it names (live test 16.10: the model asked «Подтверждаете?»
 * without a booking, then ordered into reservation 0 and 1 that did not exist). Without a reservation of this guest the
 * tool takes the guest's own active one at that time, or creates one through {@code reservation_create} from the
 * {@code date}, {@code time}, {@code partySize} given or the Concierge's position, then orders into it. A dish code the
 * model spelled loosely («COLSLAW») is read as the one menu dish it clearly means; otherwise the menu codes come back.
 *
 * <p>Live test 16.10, AERIS, booking #68 today 14:00:
 * <ul>
 * <li>with no reservationId and an empty position the guest's only booking that is not over is the one meant
 * ({@link ReservationAccess#onlyUpcoming}), before a slot is needed;</li>
 * <li>«… 2шт» ordered one portion: {@code items: [{dish, count}]} and a count written with the dish («x2», «2 шт») give
 * that many portions, 1 to {@value DishCodes#MAX_COUNT}, and the total follows;</li>
 * <li>«чизкейк с собой» about a dish already ordered made a new order: when the guest asks to pack
 * ({@link GuestAgreement#takeaway}) and every dish is already in this reservation's lunch, the order is
 * {@code NOT_ALLOWED} with {@code reason = already_ordered_takeaway} — packing is the waiter's;</li>
 * <li>Saby did not take the order and the guest read «оформлен»: {@code kitchenConfirmed} and {@code manualEntry} say
 * whether the kitchen's system has it.</li>
 * </ul>
 */
@Component
public class LunchOrderPlaceTool implements CapabilityTool {

    static final String ALREADY_PASSED = "already_passed";
    static final String ALREADY_ORDERED_TAKEAWAY = "already_ordered_takeaway";
    static final String COUNT_OUT_OF_RANGE = "count_out_of_range";
    /** What the guest is told when the kitchen's system has not got the order. */
    static final String HANDED_TO_WAITER = "Заказ передал официанту, он внесёт его в кассу.";
    static final String TAKEAWAY_QUESTION = "Завернуть то, что в заказе, или добавить ещё одну порцию?";
    private static final int REMEMBERED_RESERVATIONS = 500;

    private final BusinessLunchService lunch;
    private final BusinessLunchCatalog catalog;
    private final CapabilityProperties properties;
    private final ReservationAccess access;
    private final GuestJourneyProperties journeyProperties;
    private final Clock clock;
    /** Books the table when the guest has none yet; null in tests that only order into an existing booking. */
    private ReservationCreateTool create;
    private TableReservationService reservations;
    /** The lunch already in a reservation, from its bill; null in tests without bills. */
    private GuestBillService bills;
    /** The dishes this process ordered into a reservation, for when the bill knows none. */
    private final Map<Long, Set<String>> ordered = new ConcurrentHashMap<>();

    @Autowired
    public LunchOrderPlaceTool(BusinessLunchService lunch, BusinessLunchCatalog catalog, CapabilityProperties properties,
                               ReservationAccess access, GuestJourneyProperties journeyProperties,
                               ReservationCreateTool create, TableReservationService reservations) {
        this(lunch, catalog, properties, access, journeyProperties, Clock.systemUTC());
        this.create = create;
        this.reservations = reservations;
    }

    /** Orders only into a reservation the guest already has (see {@link #withBooking}). */
    public LunchOrderPlaceTool(BusinessLunchService lunch, BusinessLunchCatalog catalog, CapabilityProperties properties,
                               ReservationAccess access, GuestJourneyProperties journeyProperties) {
        this(lunch, catalog, properties, access, journeyProperties, Clock.systemUTC());
    }

    public LunchOrderPlaceTool(BusinessLunchService lunch, BusinessLunchCatalog catalog, CapabilityProperties properties,
                               ReservationAccess access, GuestJourneyProperties journeyProperties, Clock clock) {
        this.lunch = lunch;
        this.catalog = catalog;
        this.properties = properties;
        this.access = access;
        this.journeyProperties = journeyProperties;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setBills(GuestBillService bills) {
        this.bills = bills;
    }

    /** The booking path: without it the tool only orders into a reservation the guest already has. */
    public LunchOrderPlaceTool withBooking(ReservationCreateTool create, TableReservationService reservations) {
        this.create = create;
        this.reservations = reservations;
        return this;
    }

    @Override
    public String name() {
        return "lunch_order_place";
    }

    @Override
    public String description() {
        return "Оформить бизнес-ланч к брони гостя. Вызывай только после того, как гость ответил «да» на перечень заказа: "
                + "confirmedByGuest=true. Без «да» гостя в этом ходе — NOT_ALLOWED, reason=needs_guest_yes: перечисли заказ "
                + "и спроси подтверждение. Блюда передавай так, как их назвал гость (название из меню lunch_menu) — инструмент "
                + "сам найдёт блюдо; у гостя коды не спрашивай и гостю их не показывай. Несколько порций одного блюда — "
                + "повтори его в dishCodes по разу на порцию или передай items: [{dish, count}] (count от 1 до "
                + DishCodes.MAX_COUNT + "). Возвращает номер заказа (orderId), блюда с числом порций (dishes), сумму в рублях "
                + "(totalRub, если цены известны) и передачу на кухню: kitchenConfirmed=true — касса (Saby) приняла заказ; "
                + "manualEntry=true (kitchenConfirmed=false) — кухня заказ ещё не получила, его внесут в кассу вручную: "
                + "скажи гостю «" + HANDED_TO_WAITER + "», никогда не пиши «оформлен» или «принят». «С собой», «упакуйте», «заверните» о блюде, которое уже в заказе, — не новый заказ: "
                + "спроси один раз «" + TAKEAWAY_QUESTION + "»; завернуть заказанное — table_request с WAITER, этот "
                + "инструмент — только для новой порции после «да» гостя. Иначе NOT_ALLOWED, reason=" + ALREADY_ORDERED_TAKEAWAY + ". "
                + "Бронь не в дни или часы ланча — NOT_ALLOWED с served=false, днями (days), часами (from, to) и ближайшим "
                + "днём ланча (nextServedDay): скажи гостю, что в это время ланча нет, и предложи этот день. "
                + "Если у гостя одна активная бронь (сегодня или впереди), заказ ляжет в неё и без reservationId. "
                + "Если брони ещё нет — не выдумывай reservationId: передай date, time, partySize из позиции гостя, "
                + "и стол забронируется вместе с заказом (reservationId и reservationCreated в ответе). "
                + "BAD_ARGUMENT с unknownDishCodes и menu — выбери блюда из menu и сразу вызови снова, гостя не переспрашивай.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reservationId", Map.of("type", "integer", "description", "Номер брони гостя, если она уже есть"));
        props.put("date", Map.of("type", "string", "description", "День визита YYYY-MM-DD, если брони ещё нет"));
        props.put("time", Map.of("type", "string", "description", "Время прихода HH:mm, если брони ещё нет"));
        props.put("partySize", Map.of("type", "integer", "description", "Сколько гостей, если брони ещё нет"));
        props.put("dishCodes", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Блюда, как их назвал гость, или code из lunch_menu; по одному на порцию"));
        props.put("items", Map.of("type", "array", "items", Map.of("type", "object", "properties", Map.of(
                        "dish", Map.of("type", "string", "description", "Блюдо, как его назвал гость, или code из lunch_menu"),
                        "count", Map.of("type", "integer", "description", "Сколько порций, от 1 до " + DishCodes.MAX_COUNT)),
                        "required", List.of("dish", "count")),
                "description", "Блюда с числом порций, когда порций больше одной"));
        props.put("confirmedByGuest", Map.of("type", "boolean",
                "description", "true только если гость явно подтвердил заказ"));
        props.put("setCode", Map.of("type", "string", "description", "Код сета, если ланч подают сетами"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("confirmedByGuest"));
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
        if (ctx == null || ctx.agreement() == null || !ctx.agreement().yes()) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, GuestAgreement.NEEDS_GUEST_YES);
        }
        List<DishCodes.Portion> asked = portions(args.get("dishCodes"), args.get("items"));
        if (asked == null || asked.isEmpty()) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        for (DishCodes.Portion portion : asked) {
            if (portion.count() < 1 || portion.count() > DishCodes.MAX_COUNT) {
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("reason", COUNT_OUT_OF_RANGE);
                facts.put("dish", portion.dish());
                facts.put("count", portion.count());
                facts.put("maxCount", DishCodes.MAX_COUNT);
                return new CapabilityResult(false, facts, CapabilityResult.BAD_ARGUMENT);
            }
        }
        Optional<BusinessLunchOffer> found = catalog.find(properties.getVenueCode());
        if (found.isEmpty()) {
            return CapabilityResult.error(CapabilityResult.NOT_FOUND);
        }
        BusinessLunchOffer offer = found.get();
        List<String> codes = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (DishCodes.Portion portion : asked) {
            Optional<String> dish = DishCodes.resolve(offer, portion.dish());
            if (dish.isPresent()) {
                for (int i = 0; i < portion.count(); i++) {
                    codes.add(dish.get());
                }
            } else if (!unknown.contains(portion.dish())) {
                unknown.add(portion.dish());
            }
        }
        if (!unknown.isEmpty()) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("unknownDishCodes", unknown);
            facts.put("menu", DishCodes.menu(offer));
            return new CapabilityResult(false, facts, CapabilityResult.BAD_ARGUMENT);
        }
        String setCode = CapabilityArguments.text(args, "setCode");
        if (!offer.aLaCarte() && (setCode == null || offer.set(setCode).isEmpty())) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        boolean created = false;
        ReservationAccess.Checked checked = access.check(ctx, args);
        if (!checked.allowed()) {
            Booked booked = book(ctx, args, offer);
            if (booked.error() != null) {
                return booked.error();
            }
            checked = access.check(ctx, booked.reservationId());
            if (!checked.allowed()) {
                return checked.error();
            }
            created = booked.created();
        }
        TableReservationOrder reservation = checked.order();
        if (!BusinessLunchService.holdsATable(reservation)) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "not_active");
        }
        if (reservation.requestedStartAt() == null) {
            return CapabilityResult.error(CapabilityResult.UNAVAILABLE);
        }
        ZoneId zone = ZoneId.of(journeyProperties.getZoneId());
        ZonedDateTime start = reservation.requestedStartAt().atZone(zone);
        if (alreadyPassed(offer, reservation, start)) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, ALREADY_PASSED);
        }
        Optional<BusinessLunchService.WindowIssue> issue = BusinessLunchService.lunchTimeIssue(offer, start.toLocalDate(),
                start.toLocalTime());
        if (issue.isPresent()) {
            // The same days and hours lunch_menu gives, and the nearest lunch day, so the guest hears a way on.
            ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("reason", issue.get().name());
            facts.putAll(LunchWindowFacts.of(offer, start.toLocalDate(), false, now.toLocalDate(), now.toLocalTime()));
            return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
        }
        if (ctx.agreement().takeaway() && alreadyOrdered(reservation.id()).containsAll(codes)) {
            // «Чизкейк с собой» about the cheesecake in the order: the waiter packs it, nothing new is ordered.
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("reason", ALREADY_ORDERED_TAKEAWAY);
            facts.put("reservationId", reservation.id());
            facts.put("dishes", dishes(offer, codes));
            facts.put("ask", TAKEAWAY_QUESTION);
            return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
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
        facts.put("reservationId", reservation.id());
        facts.put("reservationCreated", created);
        facts.put("dateLabel", GuestAgreement.dateLabel(start.toLocalDate()));
        facts.put("time", GuestAgreement.factTime(start.toLocalTime()));
        remember(reservation.id(), codes);
        facts.put("dishCodes", codes);
        facts.put("dishes", dishes(offer, codes));
        facts.put("totalRub", BusinessLunchService.totalRub(offer, setCode, codes, guests));
        ExternalLunchOrderProvider.Result external = placement == null ? null : placement.external();
        facts.put("sabyStatus", external == null ? null : external.status());
        // Where the dishes are, as the placement says (#228): the till took them, or the staff enter them by hand
        // (MANUAL_ENTRY, PROVIDER_RESULT_UNKNOWN, an HTTP error) and the guest hears so. The staff card is #228's.
        boolean manual = placement != null && placement.manualEntry();
        facts.put("kitchenConfirmed", placement != null && placement.kitchenConfirmed());
        facts.put("manualEntry", manual);
        if (manual) {
            facts.put("tellGuest", HANDED_TO_WAITER);
        }
        if (created) {
            // The table is a request like any other: the hostess confirms it.
            facts.put("status", "AWAITING_HOSTESS");
        }
        return CapabilityResult.ok(facts);
    }

    private record Booked(long reservationId, boolean created, CapabilityResult error) {
        static Booked failed(CapabilityResult error) {
            return new Booked(0, false, error);
        }
    }

    /**
     * The reservation the lunch goes into when the model gave none of this guest's: the guest's own active booking at
     * that time, or a new one for the slot of the arguments or the Concierge's position. The guest's yes to the lunch
     * summary is the yes to that table. A slot outside lunch hours is refused before anything is booked.
     */
    private Booked book(CapabilityContext ctx, Map<String, Object> args, BusinessLunchOffer offer) {
        Map<String, Object> slot = new LinkedHashMap<>(ctx.slots());
        for (String key : List.of("date", "time", "partySize")) {
            Object given = args.get(key);
            if (given != null && !given.toString().isBlank()) {
                slot.put(key, given);
            }
        }
        LocalDate date;
        LocalTime time;
        Integer partySize;
        try {
            date = CapabilityArguments.date(slot, "date");
            time = CapabilityArguments.time(slot, "time");
            partySize = CapabilityArguments.integer(slot, "partySize");
        } catch (CapabilityArguments.BadArgument e) {
            date = null;
            time = null;
            partySize = null;
        }
        if (date == null || time == null) {
            // No slot, but the guest holds one booking that is not over: that is the one meant (#68 today at 14:00).
            Optional<TableReservationOrder> only = access.onlyUpcoming(ctx, properties.getVenueCode(), clock.instant());
            if (only != null && only.isPresent() && only.get().id() != null) {
                return new Booked(only.get().id(), false, null);
            }
            if (create == null || reservations == null) {
                return Booked.failed(ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "no_reservation"));
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("reason", "needs_date_time");
            return Booked.failed(new CapabilityResult(false, facts, CapabilityResult.BAD_ARGUMENT));
        }
        if (create == null || reservations == null) {
            return Booked.failed(ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "no_reservation"));
        }
        int guests = partySize == null || partySize < 1 ? 1 : partySize;
        ZoneId zone = ZoneId.of(journeyProperties.getZoneId());
        Instant startAt = date.atTime(time).atZone(zone).toInstant();
        Optional<TableReservationOrder> own = ctx.butlerAgent()
                ? (ctx.chatId() == null ? Optional.empty()
                        : reservations.findOverlappingReservation(ctx.chatId(), properties.getVenueCode(), startAt,
                                startAt.plus(ReservationCreateTool.VISIT_LENGTH)))
                : reservations.findOverlappingSessionReservation(ctx.sessionId(), ctx.visitId(), properties.getVenueCode(),
                        startAt, startAt.plus(ReservationCreateTool.VISIT_LENGTH));
        if (own.isPresent() && own.get().id() != null) {
            return new Booked(own.get().id(), false, null);
        }
        Optional<BusinessLunchService.WindowIssue> issue = BusinessLunchService.lunchTimeIssue(offer, date, time);
        if (issue.isPresent()) {
            ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("reason", issue.get().name());
            facts.putAll(LunchWindowFacts.of(offer, date, false, now.toLocalDate(), now.toLocalTime()));
            return Booked.failed(new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED));
        }
        GuestAgreement.Table table = new GuestAgreement.Table(date, time, guests);
        CapabilityContext yesToTable = new CapabilityContext(ctx.caller(), ctx.chatId(), ctx.sessionId(), ctx.visitId(),
                ctx.idempotencyKey() == null ? null : ctx.idempotencyKey() + ":table", ctx.guestName(), ctx.guestPhone(),
                new GuestAgreement(true, List.of(table)), ctx.position());
        Map<String, Object> createArgs = new LinkedHashMap<>();
        createArgs.put("date", date.toString());
        createArgs.put("time", GuestAgreement.factTime(time));
        createArgs.put("partySize", guests);
        CapabilityResult made = create.invoke(yesToTable, createArgs);
        if (!made.ok()) {
            return Booked.failed(made);
        }
        Object id = made.facts() == null ? null : made.facts().get("reservationId");
        if (!(id instanceof Number number)) {
            return Booked.failed(CapabilityResult.error(CapabilityResult.UNAVAILABLE));
        }
        return new Booked(number.longValue(), true, null);
    }

    /**
     * Whether the lunch is in the past, by the venue's clock: the visit is over, or that day's lunch hours have ended.
     * A visit that has begun but not ended may still order, as a seated guest does.
     */
    private boolean alreadyPassed(BusinessLunchOffer offer, TableReservationOrder reservation, ZonedDateTime start) {
        Instant now = clock.instant();
        Instant visitEnd = reservation.requestedEndAt() != null ? reservation.requestedEndAt()
                : start.toInstant().plus(ReservationCreateTool.VISIT_LENGTH);
        Instant lunchEnd = start.toLocalDate().atTime(offer.to()).atZone(start.getZone()).toInstant();
        return !visitEnd.isAfter(now) || !lunchEnd.isAfter(now);
    }

    /**
     * The dishes and their counts from {@code dishCodes} (a count written with the dish is read off it) and {@code items};
     * null when either is malformed or neither names a dish.
     */
    static List<DishCodes.Portion> portions(Object dishCodes, Object items) {
        List<DishCodes.Portion> portions = new ArrayList<>();
        if (dishCodes != null) {
            if (!(dishCodes instanceof List<?> list)) {
                return null;
            }
            for (Object item : list) {
                if (item == null || item.toString().isBlank()) {
                    return null;
                }
                portions.add(DishCodes.portion(item.toString()));
            }
        }
        if (items != null) {
            if (!(items instanceof List<?> list)) {
                return null;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || map.get("dish") == null || map.get("dish").toString().isBlank()) {
                    return null;
                }
                Object count = map.get("count");
                int n;
                if (count == null) {
                    n = 1;
                } else if (count instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
                    n = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number.longValue()));
                } else {
                    try {
                        n = Integer.parseInt(count.toString().trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                DishCodes.Portion written = DishCodes.portion(map.get("dish").toString());
                portions.add(new DishCodes.Portion(written.dish(), n));
            }
        }
        return portions.isEmpty() ? null : portions;
    }

    /** «Салат с сельдью и яблоком» × 2: each dish once with its count, in the order chosen, by its menu title. */
    private static List<Map<String, Object>> dishes(BusinessLunchOffer offer, List<String> codes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        codes.forEach(code -> counts.merge(code, 1, Integer::sum));
        List<Map<String, Object>> dishes = new ArrayList<>();
        counts.forEach((code, count) -> {
            Map<String, Object> dish = new LinkedHashMap<>();
            dish.put("title", DishCodes.title(offer, code));
            dish.put("count", count);
            offer.dish(code).map(BusinessLunchOffer.Dish::priceRub).ifPresent(price -> dish.put("priceRub", price));
            dishes.add(dish);
        });
        return dishes;
    }

    /** The dishes already in this reservation's lunch: the business-lunch bill's lines, and what this process ordered. */
    private Set<String> alreadyOrdered(Long reservationId) {
        Set<String> codes = new HashSet<>();
        if (reservationId == null) {
            return codes;
        }
        Set<String> placed = ordered.get(reservationId);
        if (placed != null) {
            codes.addAll(placed);
        }
        if (bills != null) {
            try {
                bills.findByReservation(reservationId)
                        .filter(bill -> bill.kind() == GuestBillKind.BUSINESS_LUNCH && bill.id() != null)
                        .ifPresent(bill -> bills.linesOf(bill.id()).forEach(line -> {
                            if (line.itemCode() != null) {
                                codes.add(line.itemCode());
                            }
                        }));
            } catch (RuntimeException e) {
                // Without the bill only what this process ordered is known; the order is not refused for that.
            }
        }
        return codes;
    }

    private void remember(Long reservationId, List<String> codes) {
        if (reservationId == null) {
            return;
        }
        if (ordered.size() > REMEMBERED_RESERVATIONS) {
            ordered.clear();
        }
        ordered.computeIfAbsent(reservationId, id -> ConcurrentHashMap.newKeySet()).addAll(codes);
    }
}
