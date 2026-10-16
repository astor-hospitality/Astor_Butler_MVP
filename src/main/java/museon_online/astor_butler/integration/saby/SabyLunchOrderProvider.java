package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import museon_online.astor_butler.domain.lunch.LunchPreorderMark;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where a business lunch order leaves Butler for Saby. The table reservation is already in Presto (written by the
 * booking flow), so the dishes are attached to that very booking with {@code PUT /retail/order/{id}/update} and
 * {@code nomenclatures}; nothing is created twice. Dish ids come from the Presto price list by exact title.
 * Switched off by default and fail-closed: without write access, without the Saby booking id or with a dish Presto
 * does not know by that name, the order is not sent and the hostess enters it by hand.
 */
@Component
@Slf4j
public class SabyLunchOrderProvider implements ExternalLunchOrderProvider {

    static final String LUNCH_ATTACHED = "SABY_LUNCH_ATTACHED";
    /** Saby answered the update with an error and the booking read back has no dishes: the kitchen surely has nothing. */
    static final String NOT_ATTACHED = "SABY_LUNCH_NOT_ATTACHED";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Below this many characters a cut wish says nothing useful, so it is left to the hostess card. */
    static final int MIN_WISH_ROOM = 12;
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final DateTimeFormatter SABY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SabyReservationProperties properties;
    private final SabyApiClient client;
    private final SabyMenuCatalog catalog;

    /** The kitchen's «готовить ко времени» line (#164). Optional so the constructors and their tests stay as they are. */
    @Autowired(required = false)
    private LunchPreorderMark preorderMark;

    @Autowired
    public SabyLunchOrderProvider(SabyReservationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this(properties, new SabyApiClient(properties, restTemplateBuilder));
    }

    SabyLunchOrderProvider(SabyReservationProperties properties, SabyApiClient client) {
        this.properties = properties;
        this.client = client;
        this.catalog = new SabyMenuCatalog(properties, client);
    }

    void setPreorderMark(LunchPreorderMark preorderMark) {
        this.preorderMark = preorderMark;
    }

    @Override
    public String providerId() {
        return SabyReservationProvider.PROVIDER_ID;
    }

    @Override
    public ExternalReservationStatus status() {
        List<String> missing = properties.missingConfiguration();
        return new ExternalReservationStatus(
                providerId(),
                properties.isEnabled(),
                properties.isEnabled() && missing.isEmpty(),
                missing,
                properties.isEnabled() ? (missing.isEmpty() ? (properties.isWriteEnabled() ? "READ_WRITE" : "READ_ONLY") : "CONFIG_REQUIRED") : "DISABLED"
        );
    }

    @Override
    public Result submit(BusinessLunchOrder order, String idempotencyKey) {
        if (!status().configured()) {
            return new Result(false, providerId(), "PROVIDER_NOT_CONFIGURED", "", "Saby is not configured; the order was not sent.");
        }
        if (!properties.isWriteEnabled()) {
            return new Result(false, providerId(), "SABY_WRITE_DISABLED", "", "Saby write is not enabled; staff enter the lunch order in Presto by hand.");
        }
        if (order == null) {
            return new Result(false, providerId(), "INVALID_REQUEST", "", "No order.");
        }
        String bookingId = order.externalReservationId();
        if (bookingId == null || !EXTERNAL_ID.matcher(bookingId).matches()) {
            return new Result(false, providerId(), "NO_SABY_BOOKING", "",
                    "The table reservation is not in Saby, so the dishes cannot be attached; staff enter the order by hand.");
        }
        if (order.guestName() == null || order.guestName().isBlank() || SabyOrderPayload.phone(order.guestPhone()).isEmpty()) {
            return new Result(false, providerId(), "GUEST_DATA_REQUIRED", "", "Saby needs the guest name and a Russian phone number.");
        }
        if (order.startAt() == null || order.guests() < 1 || order.dishes() == null || order.dishes().isEmpty()) {
            return new Result(false, providerId(), "INVALID_REQUEST", "", "Start time, guests and dishes are required.");
        }

        Long priceListId;
        List<Map<String, Object>> nomenclatures = new ArrayList<>();
        List<String> unknownDishes = new ArrayList<>();
        try {
            priceListId = catalog.priceListId(ZonedDateTime.now(ZoneId.of(properties.getZoneId()))).orElse(null);
            if (priceListId == null) {
                return new Result(false, providerId(), "NO_PRICE_LIST", "", "Saby reports no price list for the point; set SABY_PRICE_LIST_ID.");
            }
            for (BusinessLunchOrder.Item item : order.dishes()) {
                // The Saby price list names dishes its own way («… ЛАНЧ», «… 500 мл»); the menu file maps title → sabyName.
                Optional<SabyMenuCatalog.Dish> dish = catalog.findDish(item.sabyLookupName(), priceListId);
                if (dish.isEmpty()) {
                    unknownDishes.add(item.sabyLookupName());
                    continue;
                }
                nomenclatures.add(SabyOrderPayload.nomenclature(dish.get().id(), item.quantity(), priceListId, dish.get().name()));
            }
        } catch (SabyApiException exception) {
            log.warn("Saby menu lookup failed: {} (HTTP {})", exception.kind(), exception.httpStatus());
            return new Result(false, providerId(), failureStatus(exception), "", exception.getMessage());
        }
        if (!unknownDishes.isEmpty()) {
            // Not a partial order: either Presto gets the whole lunch or the hostess enters all of it.
            return new Result(false, providerId(), "DISH_NOT_IN_SABY", "",
                    "Presto has no dish named: " + String.join(", ", unknownDishes) + ". Staff enter the order by hand.");
        }

        ObjectNode snapshot;
        try {
            Read read = readable(client.get(SabyReservationProvider.ORDER_PATH + bookingId, Map.of()));
            if (read.refusal() != null) {
                return read.refusal();
            }
            snapshot = read.snapshot();
        } catch (SabyApiException exception) {
            log.warn("Saby lunch order read failed: {} (HTTP {}) error {}", exception.kind(), exception.httpStatus(), exception.errorShape());
            return new Result(false, providerId(), failureStatus(exception), "", exception.getMessage());
        }
        ObjectNode update = update(snapshot, order, nomenclatures);
        try {
            client.put(SabyReservationProvider.ORDER_PATH + bookingId + "/update", update);
        } catch (SabyApiException exception) {
            log.warn("Saby lunch order update failed: {} (HTTP {}) error {}; sent {}", exception.kind(), exception.httpStatus(),
                    exception.errorShape(), shapeOf(snapshot, order));
            return afterFailedUpdate(order, bookingId, nomenclatures, exception);
        }
        return attached(bookingId, "Lunch dishes attached to the Saby booking; the hostess still confirms the visit.");
    }

    /** A booking read from Saby: the snapshot when it can take the dishes, otherwise the refusal. */
    private record Read(ObjectNode snapshot, Result refusal) {
    }

    /**
     * Live Presto (13.10) answers GET order/{id} without product and pointId and with "nomenclatures": {} when the
     * booking has no dishes. Absent fields are fine; present ones must still match this point.
     */
    private Read readable(JsonNode existing) {
        if (!(existing instanceof ObjectNode snapshot)
                || !snapshot.path("booking").isObject()
                || !snapshot.path("customer").isObject()
                || snapshot.hasNonNull("product") && !"restaurant".equals(snapshot.path("product").asText())
                || snapshot.hasNonNull("pointId") && !snapshot.path("pointId").asText().equals(properties.getPointId().trim())) {
            return new Read(null, new Result(false, providerId(), SabyReservationProvider.RESULT_UNKNOWN, "",
                    "Cannot safely read the complete booking; staff must check the lunch order in Presto."));
        }
        if (!noDishes(snapshot)) {
            return new Read(null, new Result(false, providerId(), "EXISTING_DISHES_REQUIRE_REVIEW", "",
                    "Presto already has dishes or an unknown dish format; staff must reconcile the lunch without overwriting or duplicating items."));
        }
        return new Read(snapshot, null);
    }

    /**
     * What {@code PUT order/{id}/update} gets: the booking exactly as Saby returned it (the update replaces the whole
     * order, so nothing it holds may be dropped), with this point, the dishes and the comment lines Butler adds. Existing
     * dishes are never merged: a booking that has any is refused before this ({@code EXISTING_DISHES_REQUIRE_REVIEW}).
     */
    private ObjectNode update(ObjectNode snapshot, BusinessLunchOrder order, List<Map<String, Object>> nomenclatures) {
        ObjectNode update = snapshot.deepCopy();
        update.put("product", "restaurant");
        update.set("pointId", MAPPER.valueToTree(SabyOrderPayload.idValue(properties.getPointId())));
        update.set("nomenclatures", MAPPER.valueToTree(nomenclatures));
        if (preorderMark != null) {
            preorderMark.sabyLine(order.startAt(), order.guests()).ifPresent(line -> withPreorderMark(update, line));
        }
        withWishes(update, order.wishes());
        return update;
    }

    /**
     * After a failed update Saby is read once more, because a 5xx does not say whether the dishes landed. Dishes that are
     * exactly Butler's: the update did land. No dishes and Saby answered 5xx or 429 (it finished with the request and kept
     * nothing): one more update, rebuilt from that fresh read — idempotent, since it replaces the whole order with the same
     * dishes and is only sent while the booking still has none. A timeout is read back but never resent: the first update
     * may still be on its way, and the kitchen must not get the ticket twice. A 4xx or an auth failure is not read back.
     */
    private Result afterFailedUpdate(BusinessLunchOrder order, String bookingId, List<Map<String, Object>> nomenclatures,
                                     SabyApiException failure) {
        boolean serverError = failure.kind() == SabyApiException.Kind.HTTP_ERROR
                && (failure.httpStatus() >= 500 || failure.httpStatus() == 429);
        boolean unknown = serverError || failure.kind() == SabyApiException.Kind.TIMEOUT
                || failure.kind() == SabyApiException.Kind.INVALID_RESPONSE;
        if (!unknown) {
            return new Result(false, providerId(), failureStatus(failure), "", failure.getMessage());
        }
        JsonNode again;
        try {
            again = client.get(SabyReservationProvider.ORDER_PATH + bookingId, Map.of());
        } catch (SabyApiException exception) {
            log.warn("Saby lunch order read-back failed: {} (HTTP {})", exception.kind(), exception.httpStatus());
            return new Result(false, providerId(), SabyReservationProvider.RESULT_UNKNOWN, "", failure.getMessage());
        }
        if (again instanceof ObjectNode fresh && sameDishes(fresh.get("nomenclatures"), nomenclatures)) {
            log.info("Saby lunch order update answered {} but the dishes are on the booking", failure.httpStatus());
            return attached(bookingId, "Lunch dishes are on the Saby booking (read back after an error answer).");
        }
        Read read = readable(again);
        if (read.refusal() != null) {
            // Someone else's dishes or a booking that no longer reads: the kitchen ticket is not Butler's to guess.
            return new Result(false, providerId(), SabyReservationProvider.RESULT_UNKNOWN, "", failure.getMessage());
        }
        ObjectNode fresh = read.snapshot();
        if (!serverError) {
            return new Result(false, providerId(), NOT_ATTACHED, "",
                    "Saby did not answer the update and the booking has no dishes; it is not resent. Staff enter the order by hand.");
        }
        try {
            client.put(SabyReservationProvider.ORDER_PATH + bookingId + "/update", update(fresh, order, nomenclatures));
        } catch (SabyApiException exception) {
            log.warn("Saby lunch order update retry failed: {} (HTTP {}) error {}; sent {}", exception.kind(), exception.httpStatus(),
                    exception.errorShape(), shapeOf(fresh, order));
            boolean answered = exception.kind() == SabyApiException.Kind.HTTP_ERROR || exception.kind() == SabyApiException.Kind.AUTH_FAILED;
            return new Result(false, providerId(), answered ? NOT_ATTACHED : SabyReservationProvider.RESULT_UNKNOWN, "",
                    "Saby refused the lunch update twice; the booking has no dishes. Staff enter the order by hand.");
        }
        return attached(bookingId, "Lunch dishes attached to the Saby booking on the second update; the hostess still confirms the visit.");
    }

    private Result attached(String bookingId, String message) {
        return new Result(true, providerId(), LUNCH_ATTACHED, bookingId, message);
    }

    static boolean noDishes(JsonNode snapshot) {
        JsonNode existingDishes = snapshot.get("nomenclatures");
        return existingDishes == null || existingDishes.isNull()
                || (existingDishes.isArray() || existingDishes.isObject()) && existingDishes.isEmpty();
    }

    /** The booking's dishes are exactly the ones Butler sent: the same ids with the same counts, nothing else. */
    static boolean sameDishes(JsonNode existing, List<Map<String, Object>> sent) {
        if (existing == null || !existing.isArray() || existing.size() != sent.size()) {
            return false;
        }
        Map<Long, Long> want = new java.util.HashMap<>();
        for (Map<String, Object> item : sent) {
            want.merge(((Number) item.get("id")).longValue(), ((Number) item.get("count")).longValue(), Long::sum);
        }
        Map<Long, Long> have = new java.util.HashMap<>();
        for (JsonNode item : existing) {
            if (!item.path("id").canConvertToLong() || !item.path("count").isNumber()) {
                return false;
            }
            have.merge(item.path("id").asLong(), (long) item.path("count").asDouble(), Long::sum);
        }
        return want.equals(have);
    }

    /**
     * What the update carried, by names and codes only (16.10: a booking the hostess had moved to another table by hand
     * answered 500 where thirteen fresh ones answered 200). Field names of the order and of its booking, the codes of its
     * state, whether a table is named next to {@code woTable}, and whether the booking time has already passed —
     * never the guest, the phone or the comment.
     */
    String shapeOf(ObjectNode snapshot, BusinessLunchOrder order) {
        if (snapshot == null) {
            return "no snapshot";
        }
        JsonNode booking = snapshot.path("booking");
        StringBuilder shape = new StringBuilder();
        shape.append("order").append(SabyErrorShape.fieldNames(snapshot));
        shape.append(" booking").append(SabyErrorShape.fieldNames(booking));
        for (String code : List.of("state", "productState", "payState")) {
            if (snapshot.hasNonNull(code)) {
                shape.append(' ').append(code).append('=').append(snapshot.get(code).asText());
            }
        }
        shape.append(" tableSet=").append(booking.hasNonNull("table"));
        if (booking.has("woTable")) {
            shape.append(" woTable=").append(booking.path("woTable").asText());
        }
        shape.append(" hallSet=").append(booking.hasNonNull("hall"));
        shape.append(" dishes=").append(order.dishes().size());
        String datetime = snapshot.path("datetime").asText("");
        if (!datetime.isBlank()) {
            try {
                ZonedDateTime at = java.time.LocalDateTime.parse(datetime, SABY_DATE_TIME).atZone(ZoneId.of(properties.getZoneId()));
                shape.append(" timePassed=").append(at.isBefore(ZonedDateTime.now(ZoneId.of(properties.getZoneId()))));
            } catch (RuntimeException e) {
                shape.append(" datetime=unparsed");
            }
        }
        return shape.toString();
    }

    /**
     * The guest's wish for the kitchen (#165) rides on the booking comment, where the hostess reads it in Presto. The booking
     * Butler wrote already carries it inside the hostess line; this adds the same line only when that comment lacks it (a long
     * order cut at {@link SabyOrderPayload#MAX_GUEST_COMMENT}), and only when it still fits, so the Butler marker is never pushed out.
     * Saby's own limit on the comment is not documented; the hostess card always has the full wish.
     */
    static void withWishes(ObjectNode update, String wishes) {
        if (wishes == null || wishes.isBlank()) {
            return;
        }
        String existing = update.path("comment").asText("");
        if (existing.contains(BusinessLunchService.WISH_PREFIX)) {
            return;
        }
        String wish = wishes.trim().replaceAll("\\s+", " ");
        String lead = existing.isBlank() ? "" : existing + " · ";
        int room = SabyOrderPayload.MAX_GUEST_COMMENT - lead.length() - BusinessLunchService.WISH_PREFIX.length() - BusinessLunchService.WISH_SUFFIX.length();
        if (room < MIN_WISH_ROOM) {
            log.info("Saby comment has no room for the lunch wish; the hostess card carries it");
            return;
        }
        if (wish.length() > room) {
            wish = wish.substring(0, room - 1).stripTrailing() + "…";
        }
        update.put("comment", lead + BusinessLunchService.wishLine(wish));
    }

    /**
     * «ПРЕДЗАКАЗ: готовить ко времени 13:00, гостей 2» (#164) opens the booking comment — before the hostess line and the
     * wish — so the kitchen reads it first on the Presto ticket. Within {@link SabyOrderPayload#MAX_GUEST_COMMENT} the free
     * hostess line gives way to it; the Butler marker, and whatever follows the marker, never does. Written once: a comment
     * that already carries the line is left alone.
     */
    static void withPreorderMark(ObjectNode update, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String existing = update.path("comment").asText("").strip();
        if (existing.contains(LunchPreorderMark.PREFIX)) {
            return;
        }
        if (existing.isEmpty()) {
            update.put("comment", line);
            return;
        }
        String joined = line + LunchPreorderMark.SEPARATOR + existing;
        if (joined.length() <= SabyOrderPayload.MAX_GUEST_COMMENT) {
            update.put("comment", joined);
            return;
        }
        int marker = existing.indexOf(SabyOrderPayload.BUTLER_MARKER);
        String head = withoutTrailingSeparator(marker < 0 ? existing : existing.substring(0, marker));
        String tail = marker < 0 ? "" : existing.substring(marker);
        int room = SabyOrderPayload.MAX_GUEST_COMMENT - line.length() - LunchPreorderMark.SEPARATOR.length()
                - (tail.isEmpty() ? 0 : LunchPreorderMark.SEPARATOR.length() + tail.length());
        List<String> parts = new ArrayList<>();
        parts.add(line);
        if (!head.isEmpty() && room >= MIN_WISH_ROOM) {
            parts.add(head.length() <= room ? head : head.substring(0, room - 1).stripTrailing() + "…");
        } else if (!head.isEmpty()) {
            log.info("Saby comment has no room for the hostess line next to the pre-order mark; the hostess card carries it");
        }
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        update.put("comment", String.join(LunchPreorderMark.SEPARATOR, parts));
    }

    private static String withoutTrailingSeparator(String text) {
        String trimmed = text.strip();
        return trimmed.endsWith("·") ? trimmed.substring(0, trimmed.length() - 1).strip() : trimmed;
    }

    private static String failureStatus(SabyApiException exception) {
        return switch (exception.kind()) {
            case AUTH_FAILED -> "PROVIDER_AUTH_FAILED";
            case HTTP_ERROR -> exception.httpStatus() < 500 ? "PROVIDER_REJECTED" : SabyReservationProvider.RESULT_UNKNOWN;
            case TIMEOUT, INVALID_RESPONSE -> SabyReservationProvider.RESULT_UNKNOWN;
        };
    }

    private static TableReservationCommand commandOf(BusinessLunchOrder order) {
        return new TableReservationCommand(null, null, null, order.venueCode(), order.tableCode(), null,
                "Бизнес-ланч", order.startAt(), order.endAt(), order.guests(), order.guestName(), order.guestPhone(),
                order.comment(), null, null);
    }
}
