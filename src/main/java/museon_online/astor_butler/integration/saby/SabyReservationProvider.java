package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.external.ExternalAvailabilityResult;
import museon_online.astor_butler.domain.booking.external.ExternalBookingSnapshot;
import museon_online.astor_butler.domain.booking.external.ExternalBookingState;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Saby Presto booking adapter. Availability comes from {@code retail/hall/list}; creating and cancelling
 * bookings needs {@code SABY_WRITE_ENABLED=true}. A booking created in Saby is not a confirmed booking:
 * the hostess flow stays the source of the final answer to the guest.
 * Contract and decisions: docs/integrations/SABY_PRESTO_BOOKING_API.md.
 */
@Slf4j
@Component
public class SabyReservationProvider implements ExternalReservationProvider {

    public static final String PROVIDER_ID = "SABY";

    static final String HALL_LIST_PATH = "/retail/hall/list";
    static final String POINT_LIST_PATH = "/retail/point/list";
    static final String ORDER_CREATE_PATH = "/retail/order/create";
    static final String ORDER_PATH = "/retail/order/";
    static final String RESULT_UNKNOWN = "PROVIDER_RESULT_UNKNOWN";
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final DateTimeFormatter SABY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SabyReservationProperties properties;
    private final SabyApiClient client;
    /** Same-process duplicate guard; durable dedupe belongs to Butler via sbisExternalId (MR4). */
    private final Cache<String, ExternalReservationResult> reservations = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(10_000)
            .build();

    public SabyReservationProvider(SabyReservationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this.properties = properties;
        this.client = new SabyApiClient(properties, restTemplateBuilder);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ExternalReservationStatus status() {
        List<String> missing = properties.missingConfiguration();
        boolean configured = properties.isEnabled() && missing.isEmpty();
        String mode;
        if (!properties.isEnabled()) {
            mode = "DISABLED";
        } else if (!missing.isEmpty()) {
            mode = "CONFIG_REQUIRED";
        } else {
            mode = properties.isWriteEnabled() ? "READ_WRITE" : "READ_ONLY";
        }
        return new ExternalReservationStatus(PROVIDER_ID, properties.isEnabled(), configured, missing, mode);
    }

    @Override
    public ExternalAvailabilityResult checkAvailability(ExternalAvailabilityRequest request) {
        ExternalReservationStatus status = status();
        if (!status.configured()) {
            return ExternalAvailabilityResult.unavailableBecauseUnconfigured(PROVIDER_ID, status.missingConfiguration());
        }
        if (request == null || request.requestedStartAt() == null || request.partySize() == null || request.partySize() < 1) {
            return availability(false, "INVALID_REQUEST",
                    "Start time and party size are required for a Saby availability check.", Map.of());
        }
        if (request.venueCode() != null && !request.venueCode().equalsIgnoreCase(properties.getVenueCode())) {
            return availability(false, "UNSUPPORTED_VENUE",
                    "Saby point is configured only for venue " + properties.getVenueCode() + ".",
                    Map.of("venueCode", request.venueCode()));
        }

        String localStart = localStart(request.requestedStartAt());
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("pointId", properties.getPointId());
        query.put("date", localStart);
        if (properties.getHallId() != null && !properties.getHallId().isBlank()) {
            query.put("hallId", properties.getHallId());
        }

        JsonNode response;
        try {
            response = client.get(HALL_LIST_PATH, query);
        } catch (SabyApiException exception) {
            log.warn("Saby availability check failed: {} (HTTP {})", exception.kind(), exception.httpStatus());
            return availability(false, failureStatus(exception), exception.getMessage(),
                    Map.of("httpStatus", exception.httpStatus()));
        }

        JsonNode halls = response.path("halls");
        if (!halls.isArray()) {
            return availability(false, "PROVIDER_INVALID_RESPONSE",
                    "Saby hall/list response has no halls array.", Map.of());
        }
        List<Map<String, Object>> candidates = freeTables(halls, request.partySize());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pointId", properties.getPointId());
        metadata.put("localStart", localStart);
        metadata.put("candidates", candidates);
        metadata.put("hasMore", response.path("outcome").path("hasMore").asBoolean(false)
                || response.path("outcome").path("hasmore").asBoolean(false));
        if (candidates.isEmpty()) {
            return availability(false, "NO_TABLES_AVAILABLE",
                    "Saby has no free table for this time and party size.", metadata);
        }
        return availability(true, "AVAILABLE",
                "Saby reports free tables; final confirmation stays with the hostess.", metadata);
    }

    @Override
    public ExternalReservationResult reserve(TableReservationCommand command, String idempotencyKey) {
        ExternalReservationStatus status = status();
        if (!status.configured()) {
            return ExternalReservationResult.rejectedBecauseUnconfigured(PROVIDER_ID, status.missingConfiguration());
        }
        if (!properties.isWriteEnabled()) {
            return reservation("SABY_WRITE_DISABLED",
                    "Saby booking write is not enabled; keep the hostess confirmation flow.", Map.of());
        }
        if (SabyOrderPayload.isBlank(idempotencyKey)) {
            return reservation("INVALID_REQUEST", "An idempotency key is required for a Saby booking.", Map.of());
        }
        if (command == null || command.requestedStartAt() == null || command.partySize() == null || command.partySize() < 1) {
            return reservation("INVALID_REQUEST", "Start time and party size are required for a Saby booking.", Map.of());
        }
        if (command.venueCode() != null && !command.venueCode().equalsIgnoreCase(properties.getVenueCode())) {
            return reservation("UNSUPPORTED_VENUE",
                    "Saby point is configured only for venue " + properties.getVenueCode() + ".",
                    Map.of("venueCode", command.venueCode()));
        }
        if (SabyOrderPayload.isBlank(command.guestName()) || SabyOrderPayload.isBlank(command.guestPhone())) {
            return reservation("GUEST_DATA_REQUIRED",
                    "Saby needs the guest name and phone; the request stays with the hostess.", Map.of());
        }
        if (SabyOrderPayload.phone(command.guestPhone()).isEmpty()) {
            return reservation("GUEST_DATA_REQUIRED",
                    "Saby needs a Russian phone number as digits; the request stays with the hostess.",
                    Map.of("reason", "PHONE_FORMAT"));
        }

        // Atomic per key: a concurrent or repeated call with the same key never sends a second create.
        // Only outcomes that must not be retried are cached; rejections can be retried after a fix.
        ExternalReservationResult[] notCached = new ExternalReservationResult[1];
        ExternalReservationResult cached = reservations.get(idempotencyKey, key -> {
            ExternalReservationResult result = createOrder(command, key);
            if (result.created() || RESULT_UNKNOWN.equals(result.status())) {
                return result;
            }
            notCached[0] = result;
            return null;
        });
        return cached != null ? cached : notCached[0];
    }

    /**
     * Resolves a {@code PROVIDER_RESULT_UNKNOWN} by hand: the hostess found the booking in Saby under the Butler marker,
     * so Butler adopts its id and a repeat with the same key never sends a second create. Returns the adopted result.
     */
    public ExternalReservationResult adopt(String idempotencyKey, String externalId) {
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            return reservation("INVALID_EXTERNAL_ID", "Saby booking id must be a UUID-like value.", Map.of());
        }
        ExternalReservationResult adopted = new ExternalReservationResult(true, true, PROVIDER_ID, "SABY_ORDER_ADOPTED",
                externalId, "Booking found in Saby by hand and attached to this request; not confirmed until Saby says so.",
                List.of(), Map.of("butlerMarker", SabyOrderPayload.BUTLER_MARKER + idempotencyKey));
        reservations.put(idempotencyKey, adopted);
        return adopted;
    }

    /**
     * Resolves a {@code PROVIDER_RESULT_UNKNOWN} the other way: the hostess found nothing in Saby, so the key is
     * released and the next reserve with it may send one more create. Returns whether anything was cached for it.
     */
    public boolean forget(String idempotencyKey) {
        boolean known = reservations.getIfPresent(idempotencyKey) != null;
        reservations.invalidate(idempotencyKey);
        return known;
    }

    /** Reads the booking status and maps the documented codes; see {@link SabyBookingState}. */
    public SabyOrderResult state(String externalId) {
        SabyOrderResult precheck = orderPrecheck(externalId, false);
        if (precheck != null) {
            return precheck;
        }
        JsonNode response;
        try {
            response = client.get(ORDER_PATH + externalId + "/state", Map.of());
        } catch (SabyApiException exception) {
            log.warn("Saby booking state failed: {} (HTTP {})", exception.kind(), exception.httpStatus());
            return SabyOrderResult.failure(externalId, failureStatus(exception), exception.getMessage());
        }
        Integer state = intOrNull(response, "state");
        if (state == null) {
            return SabyOrderResult.failure(externalId, "PROVIDER_INVALID_RESPONSE",
                    "Saby order state response has no state.");
        }
        Integer productState = intOrNull(response, "productState");
        SabyBookingState mapped = SabyBookingState.of(state, productState);
        return new SabyOrderResult(true, "SABY_STATE_" + state, externalId, state, productState,
                intOrNull(response, "payState"), mapped,
                mapped == SabyBookingState.UNKNOWN
                        ? "Saby booking state code is outside the documented set; treat as not confirmed."
                        : "Saby booking state " + mapped + " (documented codes).");
    }

    @Override
    public boolean cancelReservation(String externalReservationId) {
        return cancel(externalReservationId).ok();
    }

    @Override
    public ExternalBookingSnapshot fetchReservationState(String externalReservationId) {
        SabyOrderResult result;
        try {
            result = state(externalReservationId);
        } catch (RuntimeException e) {
            log.warn("Saby booking state read failed unexpectedly for a booking: {}", e.toString());
            return ExternalBookingSnapshot.unknown(PROVIDER_ID, externalReservationId, "PROVIDER_ERROR");
        }
        if (!result.ok()) {
            return ExternalBookingSnapshot.unknown(PROVIDER_ID, externalReservationId, result.status());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", result.state());
        metadata.put("productState", result.productState());
        metadata.put("payState", result.payState());
        return new ExternalBookingSnapshot(PROVIDER_ID, externalReservationId, toExternal(result.bookingState()),
                result.status(), metadata);
    }

    private static ExternalBookingState toExternal(SabyBookingState state) {
        return switch (state == null ? SabyBookingState.UNKNOWN : state) {
            case PENDING -> ExternalBookingState.PENDING;
            case CONFIRMED -> ExternalBookingState.CONFIRMED;
            case COMPLETED -> ExternalBookingState.COMPLETED;
            case CANCELLED -> ExternalBookingState.CANCELLED;
            case UNKNOWN -> ExternalBookingState.UNKNOWN;
        };
    }

    /** Requests cancellation. Never retried; check {@link #state(String)} afterwards. */
    public SabyOrderResult cancel(String externalId) {
        SabyOrderResult precheck = orderPrecheck(externalId, true);
        if (precheck != null) {
            return precheck;
        }
        try {
            client.put(ORDER_PATH + externalId + "/cancel");
        } catch (SabyApiException exception) {
            log.warn("Saby booking cancel failed: {} (HTTP {})", exception.kind(), exception.httpStatus());
            String status = switch (exception.kind()) {
                case AUTH_FAILED -> "PROVIDER_AUTH_FAILED";
                case HTTP_ERROR -> exception.httpStatus() < 500 ? "PROVIDER_REJECTED" : RESULT_UNKNOWN;
                case TIMEOUT, INVALID_RESPONSE -> RESULT_UNKNOWN;
            };
            return SabyOrderResult.failure(externalId, status, exception.getMessage());
        }
        return new SabyOrderResult(true, "CANCEL_REQUESTED", externalId, null, null, null, SabyBookingState.UNKNOWN,
                "Saby accepted the cancellation request; check the booking state to confirm.");
    }

    private SabyOrderResult orderPrecheck(String externalId, boolean write) {
        ExternalReservationStatus status = status();
        if (!status.configured()) {
            return SabyOrderResult.failure(externalId, "PROVIDER_NOT_CONFIGURED",
                    "Saby provider is not configured.");
        }
        if (write && !properties.isWriteEnabled()) {
            return SabyOrderResult.failure(externalId, "SABY_WRITE_DISABLED",
                    "Saby booking write is not enabled.");
        }
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            return SabyOrderResult.failure(externalId, "INVALID_EXTERNAL_ID",
                    "Saby booking id must be a UUID-like value.");
        }
        return null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private ExternalReservationResult createOrder(TableReservationCommand command, String idempotencyKey) {
        String localStart = localStart(command.requestedStartAt());
        Map<String, Object> body = SabyOrderPayload.create(command, idempotencyKey, properties, localStart);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pointId", properties.getPointId());
        metadata.put("localStart", localStart);
        metadata.put("tableSelection", "SABY_ADMINISTRATOR");
        metadata.put("butlerMarker", SabyOrderPayload.BUTLER_MARKER + idempotencyKey);

        JsonNode response;
        try {
            response = client.post(ORDER_CREATE_PATH, body);
        } catch (SabyApiException exception) {
            log.warn("Saby booking create failed: {} (HTTP {})", exception.kind(), exception.httpStatus());
            metadata.put("httpStatus", exception.httpStatus());
            return switch (exception.kind()) {
                // Authorization happens before the create is processed, so nothing was booked.
                case AUTH_FAILED -> reservation("PROVIDER_AUTH_FAILED", exception.getMessage(), metadata);
                case HTTP_ERROR -> exception.httpStatus() < 500
                        ? reservation("PROVIDER_REJECTED", exception.getMessage(), metadata)
                        : resultUnknown(idempotencyKey, metadata);
                case TIMEOUT, INVALID_RESPONSE -> resultUnknown(idempotencyKey, metadata);
            };
        }

        List<String> responseFields = new ArrayList<>();
        response.fieldNames().forEachRemaining(responseFields::add);
        metadata.put("responseFields", responseFields);
        String externalId = firstText(response, "externalId", "id", "key");
        if (externalId.isBlank()) {
            return resultUnknown(idempotencyKey, metadata);
        }
        if (!EXTERNAL_ID.matcher(externalId).matches()) {
            // Same policy as state/cancel: an id those calls would refuse is not a usable booking id. The value itself
            // stays out of the result, only the fact and its length, so nothing odd from Saby ends up in logs or chats.
            log.warn("Saby create answered with an unusable booking id ({} chars) for {}", externalId.length(),
                    SabyOrderPayload.BUTLER_MARKER + idempotencyKey);
            metadata.put("externalIdRejected", true);
            metadata.put("externalIdLength", externalId.length());
            return resultUnknown(idempotencyKey, metadata);
        }
        return new ExternalReservationResult(
                true,
                true,
                PROVIDER_ID,
                "SABY_ORDER_CREATED_UNCONFIRMED",
                externalId,
                "Booking created in Saby; it is not confirmed until the hostess or the Saby status confirms it.",
                List.of(),
                metadata
        );
    }

    private ExternalReservationResult resultUnknown(String idempotencyKey, Map<String, Object> metadata) {
        return reservation(RESULT_UNKNOWN,
                "Saby did not confirm whether the booking was created; check Saby for '"
                        + SabyOrderPayload.BUTLER_MARKER + idempotencyKey + "' before any retry.",
                metadata);
    }

    private static ExternalReservationResult reservation(String status, String message, Map<String, Object> metadata) {
        return new ExternalReservationResult(false, true, PROVIDER_ID, status, "", message, List.of(), metadata);
    }

    /** A booking id is a non-blank string or an integral number; a boolean, object or array is not an id. */
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            String text = value.isTextual() ? value.asText().trim() : value.isIntegralNumber() ? value.asText() : "";
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String localStart(Instant instant) {
        return SABY_DATE_TIME.format(instant.atZone(ZoneId.of(properties.getZoneId())));
    }

    /**
     * Setup diagnostic: lists Presto booking points to find {@code SABY_POINT_ID} and halls on the first run.
     * Needs only credentials, not a point id. Throws {@link SabyApiException} on failure.
     */
    public JsonNode listPoints() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("product", "restaurant");
        query.put("pageSize", 100);
        return client.get(POINT_LIST_PATH, query);
    }

    private List<Map<String, Object>> freeTables(JsonNode halls, int partySize) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (JsonNode hall : halls) {
            if (!hall.path("active").asBoolean(true)) {
                continue;
            }
            for (JsonNode table : hall.path("items")) {
                // A table without an explicit busy flag is treated as unknown and skipped.
                boolean free = table.has("busy") && !table.path("busy").asBoolean(true);
                boolean bookable = !table.path("isBookingLocked").asBoolean(false);
                boolean visible = table.path("visible").asBoolean(true);
                boolean fits = table.path("capacity").asInt(0) >= partySize;
                if (free && bookable && visible && fits) {
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("hallId", hall.path("id").asLong());
                    candidate.put("hallName", hall.path("name").asText(""));
                    candidate.put("tableId", table.path("id").asLong());
                    candidate.put("tableName", table.path("name").asText(""));
                    candidate.put("capacity", table.path("capacity").asInt());
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private static String failureStatus(SabyApiException exception) {
        return switch (exception.kind()) {
            case TIMEOUT -> "PROVIDER_TIMEOUT";
            case AUTH_FAILED -> "PROVIDER_AUTH_FAILED";
            case INVALID_RESPONSE -> "PROVIDER_INVALID_RESPONSE";
            case HTTP_ERROR -> "PROVIDER_ERROR";
        };
    }

    private static ExternalAvailabilityResult availability(
            boolean available,
            String status,
            String message,
            Map<String, Object> metadata
    ) {
        return new ExternalAvailabilityResult(available, true, PROVIDER_ID, status, message, List.of(), metadata);
    }
}
