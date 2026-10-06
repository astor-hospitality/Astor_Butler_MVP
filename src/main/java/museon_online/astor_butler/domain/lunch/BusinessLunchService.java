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
import java.util.List;
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

    private final TableReservationService tableReservationService;
    private final List<ExternalLunchOrderProvider> externalProviders;
    private final BookingTimeProvider timeProvider;

    @Value("${telegram.booking.manager-chat-id:876857557}")
    private Long managerTelegramId;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    public enum WindowIssue {
        NOT_A_LUNCH_DAY,
        OUTSIDE_LUNCH_HOURS,
        ALREADY_PASSED
    }

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
     * @param alreadyPlaced the guest already has a request for this very time, so no second table was held
     * @param external what the venue's own system answered; {@code MANUAL_ENTRY} when none is switched on
     */
    public record Placement(
            TableReservationOrder reservation,
            boolean alreadyPlaced,
            BusinessLunchOrder order,
            ExternalLunchOrderProvider.Result external
    ) {
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
        BusinessLunchOffer.LunchSet set = offer.set(request.setCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown business lunch set: " + request.setCode()));
        if (request.guests() < 1 || request.guests() > MAX_GUESTS) {
            throw new IllegalArgumentException("Business lunch is for 1 to " + MAX_GUESTS + " guests");
        }
        windowIssue(offer, request.date(), request.time()).ifPresent(issue -> {
            throw new IllegalArgumentException("Business lunch cannot be placed: " + issue);
        });

        Instant startAt = request.date().atTime(request.time()).atZone(BookingTimeProvider.VENUE_ZONE).toInstant();
        Instant endAt = startAt.plusSeconds(offer.seating() * 60L);
        List<BusinessLunchOrder.Item> dishes = items(offer, request.dishCodes());

        Optional<TableReservationOrder> existing = tableReservationService.listActiveReservationsByChatId(request.chatId()).stream()
                .filter(order -> startAt.equals(order.requestedStartAt()))
                .findFirst();
        if (existing.isPresent()) {
            return new Placement(existing.get(), true, order(request, set, dishes, existing.get(), startAt, endAt), null);
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

    private List<BusinessLunchOrder.Item> items(BusinessLunchOffer offer, List<String> dishCodes) {
        List<BusinessLunchOrder.Item> items = new ArrayList<>();
        for (String code : dishCodes == null ? List.<String>of() : dishCodes) {
            BusinessLunchOffer.Dish dish = offer.dish(code)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown business lunch dish: " + code));
            items.add(new BusinessLunchOrder.Item(offer.courseOf(code).map(BusinessLunchOffer.Course::code).orElse(""), dish.code(), dish.title()));
        }
        return List.copyOf(items);
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
                set.code(),
                set.title(),
                set.priceRub(),
                dishes,
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
        comment.append(": ").append(request.guests()).append(" × «").append(set.title()).append("»");
        if (set.priceRub() != null) {
            comment.append(", ").append(set.priceRub()).append(" ₽");
        }
        comment.append(". ");
        if (!dishes.isEmpty()) {
            comment.append(String.join(", ", dishes.stream().map(BusinessLunchOrder.Item::dishTitle).toList())).append(". ");
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
