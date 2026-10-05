package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.external.ExternalAvailabilityResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saby Presto booking adapter. Read-only for now: availability comes from {@code retail/hall/list};
 * writes stay blocked until the write path is implemented behind {@code SABY_WRITE_ENABLED}.
 * Contract and decisions: docs/integrations/SABY_PRESTO_BOOKING_API.md.
 */
@Slf4j
@Component
public class SabyReservationProvider implements ExternalReservationProvider {

    public static final String PROVIDER_ID = "SABY";

    static final String HALL_LIST_PATH = "/retail/hall/list";
    private static final DateTimeFormatter SABY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SabyReservationProperties properties;
    private final SabyApiClient client;

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
            mode = "READ_ONLY";
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

        String localStart = SABY_DATE_TIME.format(request.requestedStartAt().atZone(ZoneId.of(properties.getZoneId())));
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
        return new ExternalReservationResult(
                false,
                true,
                PROVIDER_ID,
                "SABY_WRITE_DISABLED",
                "",
                "Saby booking write is not enabled; keep the hostess confirmation flow.",
                List.of(),
                Map.of()
        );
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
