package museon_online.astor_butler.integration.saby;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
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
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final DateTimeFormatter SABY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SabyReservationProperties properties;
    private final SabyApiClient client;
    private final SabyMenuCatalog catalog;

    @Autowired
    public SabyLunchOrderProvider(SabyReservationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this(properties, new SabyApiClient(properties, restTemplateBuilder));
    }

    SabyLunchOrderProvider(SabyReservationProperties properties, SabyApiClient client) {
        this.properties = properties;
        this.client = client;
        this.catalog = new SabyMenuCatalog(properties, client);
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
                Optional<SabyMenuCatalog.Dish> dish = catalog.findDish(item.dishTitle(), priceListId);
                if (dish.isEmpty()) {
                    unknownDishes.add(item.dishTitle());
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

        String localStart = SABY_DATE_TIME.format(order.startAt().atZone(ZoneId.of(properties.getZoneId())));
        Map<String, Object> body = SabyOrderPayload.withNomenclatures(
                SabyOrderPayload.create(commandOf(order), String.valueOf(order.tableReservationId()), properties, localStart),
                nomenclatures);
        try {
            client.put(SabyReservationProvider.ORDER_PATH + bookingId + "/update", body);
        } catch (SabyApiException exception) {
            log.warn("Saby lunch order update failed: {} (HTTP {})", exception.kind(), exception.httpStatus());
            return new Result(false, providerId(), failureStatus(exception), "", exception.getMessage());
        }
        return new Result(true, providerId(), LUNCH_ATTACHED, bookingId,
                "Lunch dishes attached to the Saby booking; the hostess still confirms the visit.");
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
