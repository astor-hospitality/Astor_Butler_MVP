package museon_online.astor_butler.domain.lunch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.fsm.scenario.BookingTimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a finished business lunch dialogue into something the venue acts on: a held table with a card for the hostess,
 * and the order handed to the venue's own system when one is connected. The hostess still confirms; nothing here does.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessLunchService {

    public static final String SEATING_LABEL = "Бизнес-ланч";
    public static final int MAX_GUESTS = 20;
    public static final int MAX_PORTIONS = 60;

    private final TableReservationService tableReservationService;
    private final List<ExternalLunchOrderProvider> externalProviders;
    private final BookingTimeProvider timeProvider;
    private final BusinessLunchCatalog catalog;

    @Value("${astor.business-lunch.default-venue-code:AERIS}")
    private String defaultVenueCode = "AERIS";

    @Value("${telegram.booking.manager-chat-id:876857557}")
    private Long managerTelegramId;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    public enum WindowIssue {
        NOT_A_LUNCH_DAY,
        OUTSIDE_LUNCH_HOURS,
        ALREADY_PASSED
    }

    /**
     * @param setCode the chosen set, or null for an à la carte order
     * @param dishCodes one code per slot of the set; for an à la carte order one code per portion, repeated as ordered
     */
    public record Request(
            Long chatId,
            Long telegramUserId,
            BusinessLunchOffer offer,
            String setCode,
            List<String> dishCodes,
            int guests,
            LocalDate date,
            LocalTime time,
            String guestName,
            String guestPhone,
            String comment,
            String source,
            String conciergeRequestId
    ) {
    }

    /**
     * @param reservation the table held for this lunch; when {@code alreadyPlaced}, the guest's earlier request that is in its way
     * @param alreadyPlaced the guest already holds a request at this venue for a time that crosses the lunch, so no second table was held
     * @param order what was ordered; null when nothing was placed
     * @param external what the venue's own system answered; {@code MANUAL_ENTRY} when none is switched on; null when nothing was placed
     */
    public record Placement(
            TableReservationOrder reservation,
            boolean alreadyPlaced,
            BusinessLunchOrder order,
            ExternalLunchOrderProvider.Result external
    ) {
    }

    /**
     * The lunch offer a reservation was made under, by the mark this service puts on it. Empty for any other reservation.
     * The mark is also the start of the comment, because the guest may later replace the seating wish.
     */
    public Optional<BusinessLunchOffer> offerOf(TableReservationOrder reservation) {
        if (reservation == null) {
            return Optional.empty();
        }
        boolean lunch = SEATING_LABEL.equals(reservation.seatingPreference())
                || reservation.guestComment() != null && reservation.guestComment().startsWith(SEATING_LABEL);
        return lunch ? catalog.find(defaultVenueCode) : Optional.empty();
    }

    /** Why this day or time cannot be a business lunch. A null time checks the day alone. */
    public Optional<WindowIssue> windowIssue(BusinessLunchOffer offer, LocalDate date, LocalTime time) {
        if (date == null) {
            return Optional.empty();
        }
        LocalDate today = timeProvider.today();
        if (date.isBefore(today)) {
            return Optional.of(WindowIssue.ALREADY_PASSED);
        }
        if (!offer.days().contains(date.getDayOfWeek())) {
            return Optional.of(WindowIssue.NOT_A_LUNCH_DAY);
        }
        if (date.equals(today) && !timeProvider.nowTime().isBefore(offer.to())) {
            return Optional.of(WindowIssue.ALREADY_PASSED);
        }
        if (time == null) {
            return Optional.empty();
        }
        if (time.isBefore(offer.from()) || !time.isBefore(offer.to())) {
            return Optional.of(WindowIssue.OUTSIDE_LUNCH_HOURS);
        }
        if (date.equals(today) && !time.isAfter(timeProvider.nowTime())) {
            return Optional.of(WindowIssue.ALREADY_PASSED);
        }
        return Optional.empty();
    }

    public Placement place(Request request) {
        BusinessLunchOffer offer = request.offer();
        BusinessLunchOffer.LunchSet set = offer.aLaCarte() ? null : offer.set(request.setCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown business lunch set: " + request.setCode()));
        if (request.guests() < 1 || request.guests() > MAX_GUESTS) {
            throw new IllegalArgumentException("Business lunch is for 1 to " + MAX_GUESTS + " guests");
        }
        windowIssue(offer, request.date(), request.time()).ifPresent(issue -> {
            throw new IllegalArgumentException("Business lunch cannot be placed: " + issue);
        });

        Instant startAt = request.date().atTime(request.time()).atZone(BookingTimeProvider.VENUE_ZONE).toInstant();
        Instant endAt = startAt.plusSeconds(offer.seating() * 60L);
        List<BusinessLunchOrder.Item> dishes = items(offer, request.dishCodes(), set == null ? 1 : request.guests());
        if (dishes.isEmpty()) {
            throw new IllegalArgumentException("Business lunch order has no dishes");
        }

        // The same rule as for an ordinary table: a request of this guest that crosses the lunch is in its way,
        // whether it starts at the same minute or a quarter of an hour later.
        Optional<TableReservationOrder> inTheWay = tableReservationService.findOverlappingReservation(request.chatId(), offer.venueCode(), startAt, endAt);
        if (inTheWay.isPresent()) {
            return new Placement(inTheWay.get(), true, null, null);
        }

        Optional<ExternalLunchOrderProvider> provider = externalProviders.stream()
                .filter(candidate -> isReady(candidate.status()))
                .findFirst();
        TableReservationOrder reservation = tableReservationService.createReservation(new TableReservationCommand(
                request.chatId(),
                request.telegramUserId(),
                null,
                offer.venueCode(),
                null,
                null,
                SEATING_LABEL,
                startAt,
                endAt,
                request.guests(),
                request.guestName(),
                request.guestPhone(),
                hostessComment(request, set, dishes, provider.isEmpty()),
                managerTelegramId,
                hostessChatId
        ));
        BusinessLunchOrder order = order(request, set, dishes, reservation, startAt, endAt);
        return new Placement(reservation, false, order, provider.map(ready -> submit(ready, order)).orElseGet(ExternalLunchOrderProvider.Result::manualEntry));
    }

    private ExternalLunchOrderProvider.Result submit(ExternalLunchOrderProvider provider, BusinessLunchOrder order) {
        ExternalLunchOrderProvider.Result result;
        try {
            result = provider.submit(order, "astor-lunch-" + order.tableReservationId());
        } catch (RuntimeException e) {
            log.warn("Business lunch order was not accepted by {}: reservation={}, reason={}", provider.providerId(), order.tableReservationId(), e.toString());
            return ExternalLunchOrderProvider.Result.failed(provider.providerId(), e.getClass().getSimpleName());
        }
        if (result == null) {
            return ExternalLunchOrderProvider.Result.failed(provider.providerId(), "empty provider result");
        }
        if (result.accepted() && result.externalOrderId() != null && !result.externalOrderId().isBlank()) {
            tableReservationService.attachExternalId(order.tableReservationId(), result.externalOrderId());
        }
        return result;
    }

    private boolean isReady(ExternalReservationStatus status) {
        return status != null && status.enabled() && status.configured();
    }

    /** One line per dish in the order the guest chose them. A set gives every guest each of its dishes. */
    private List<BusinessLunchOrder.Item> items(BusinessLunchOffer offer, List<String> dishCodes, int portionsPerCode) {
        Map<String, Integer> portions = new LinkedHashMap<>();
        for (String code : dishCodes == null ? List.<String>of() : dishCodes) {
            if (code != null) {
                portions.merge(code, portionsPerCode, Integer::sum);
            }
        }
        if (portions.values().stream().mapToInt(Integer::intValue).sum() > MAX_PORTIONS * Math.max(1, portionsPerCode)) {
            throw new IllegalArgumentException("Business lunch order is too large");
        }
        List<BusinessLunchOrder.Item> items = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : portions.entrySet()) {
            BusinessLunchOffer.Dish dish = offer.dish(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown business lunch dish: " + entry.getKey()));
            String course = offer.courseOf(dish.code()).map(BusinessLunchOffer.Course::code).orElse("");
            items.add(new BusinessLunchOrder.Item(course, dish.code(), dish.title(), entry.getValue(), dish.priceRub()));
        }
        return List.copyOf(items);
    }

    /** What the guest will pay, when the venue has published the prices it takes to tell. */
    private Integer total(BusinessLunchOffer.LunchSet set, List<BusinessLunchOrder.Item> dishes, int guests) {
        if (set != null) {
            return set.priceRub() == null ? null : set.priceRub() * guests;
        }
        if (dishes.stream().anyMatch(item -> item.priceRub() == null)) {
            return null;
        }
        return dishes.stream().mapToInt(item -> item.priceRub() * item.quantity()).sum();
    }

    private BusinessLunchOrder order(
            Request request,
            BusinessLunchOffer.LunchSet set,
            List<BusinessLunchOrder.Item> dishes,
            TableReservationOrder reservation,
            Instant startAt,
            Instant endAt
    ) {
        return new BusinessLunchOrder(
                request.offer().venueCode(),
                reservation.id(),
                reservation.tableCode(),
                startAt,
                endAt,
                request.guests(),
                set == null ? null : set.code(),
                set == null ? null : set.title(),
                set == null ? null : set.priceRub(),
                dishes,
                total(set, dishes, request.guests()),
                request.guestName(),
                request.guestPhone(),
                request.comment(),
                request.source(),
                request.conciergeRequestId()
        );
    }

    /** The one line the hostess reads on her card: what was ordered, where it came from and whether she has to enter it herself. */
    private String hostessComment(Request request, BusinessLunchOffer.LunchSet set, List<BusinessLunchOrder.Item> dishes, boolean manualEntry) {
        StringBuilder comment = new StringBuilder(SEATING_LABEL);
        if ("CONCIERGE".equals(request.source())) {
            comment.append(" из Concierge");
        }
        comment.append(": ");
        if (set != null) {
            comment.append(request.guests()).append(" × «").append(set.title()).append("»");
            if (set.priceRub() != null) {
                comment.append(", ").append(set.priceRub()).append(" ₽");
            }
            comment.append(". ").append(String.join(", ", dishes.stream().map(BusinessLunchOrder.Item::dishTitle).toList())).append(". ");
        } else {
            comment.append(String.join(", ", dishes.stream().map(item -> item.dishTitle() + " × " + item.quantity()).toList())).append(". ");
            Integer total = total(null, dishes, request.guests());
            if (total != null) {
                comment.append("Итого ").append(total).append(" ₽. ");
            }
        }
        if (request.comment() != null && !request.comment().isBlank()) {
            comment.append("Пожелание: ").append(request.comment().trim()).append(". ");
        }
        if (manualEntry) {
            comment.append("В Saby внести вручную.");
        }
        return comment.toString().trim();
    }
}
