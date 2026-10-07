package museon_online.astor_butler.domain.shift;

import museon_online.astor_butler.domain.billing.*;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds the morning plan and the end-of-day look back from what Butler already holds. */
@Service
public class ShiftBriefingService {
    private static final int LIMIT = 500;
    private final TableReservationRepository reservations;
    private final GuestBillRepository bills;
    private final VisitReviewRepository reviews;
    private final BusinessLunchCatalog lunches;
    private final ShiftBriefingProperties properties;

    public ShiftBriefingService(TableReservationRepository reservations, GuestBillRepository bills,
                                VisitReviewRepository reviews, BusinessLunchCatalog lunches,
                                ShiftBriefingProperties properties) {
        this.reservations = reservations;
        this.bills = bills;
        this.reviews = reviews;
        this.lunches = lunches;
        this.properties = properties;
    }

    public ShiftBriefing morning(LocalDate date) { return build(ShiftBriefing.Kind.MORNING, date); }

    public ShiftBriefing evening(LocalDate date) { return build(ShiftBriefing.Kind.EVENING, date); }

    ShiftBriefing build(ShiftBriefing.Kind kind, LocalDate date) {
        ZoneId zone = properties.zone();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        String venue = properties.getVenueCode();

        List<TableReservationOrder> orders = reservations.findOrdersStartingBetween(venue, from, to, LIMIT);
        var slots = new ArrayList<ShiftBriefing.Slot>();
        int guests = 0, awaiting = 0, cancelled = 0;
        var lunchBookings = new ArrayList<String>();
        var formatter = DateTimeFormatter.ofPattern("HH:mm");
        Optional<BusinessLunchOffer> offer = lunches.find(venue);

        for (TableReservationOrder order : orders) {
            boolean dead = order.status() == TableReservationStatus.CANCELLED
                    || order.status() == TableReservationStatus.REJECTED;
            if (dead) {
                cancelled++;
                continue;
            }
            if (order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) awaiting++;
            if (order.status() == TableReservationStatus.AWAITING_GUEST_SELECTION) continue;
            int party = order.partySize() == null ? 0 : order.partySize();
            guests += party;
            LocalTime start = LocalDateTime.ofInstant(order.requestedStartAt(), zone).toLocalTime();
            boolean lunch = isLunch(offer, order.requestedStartAt(), zone);
            String table = order.tableDisplayName() != null ? order.tableDisplayName()
                    : order.tableCode() != null ? order.tableCode() : "стол не назначен";
            var slot = new ShiftBriefing.Slot(start.format(formatter), order.guestName(), table,
                    order.preferredZone(), party, order.status().name(), lunch, order.guestComment());
            slots.add(slot);
            if (lunch) lunchBookings.add(slot.time() + " · " + table + " · " + party);
        }

        List<GuestBill> dayBills = bills.findOpenedBetween(venue, from, to, LIMIT);
        int issued = 0, paid = 0;
        long estimated = 0, venueTotal = 0;
        for (GuestBill bill : dayBills) {
            if (bill.status() == GuestBillStatus.ISSUED) issued++;
            if (bill.status() == GuestBillStatus.PAID) paid++;
            if (bill.estimateMinor() != null) estimated += bill.estimateMinor();
            if (bill.venueAmountMinor() != null) venueTotal += bill.venueAmountMinor();
        }
        var billSummary = new ShiftBriefing.Bills(dayBills.size(), issued, paid, dayBills.size() - paid, estimated, venueTotal);

        List<VisitReview> dayReviews = reviews.findCreatedBetween(from, to, LIMIT);
        int answered = 0, up = 0, down = 0, stars = 0;
        for (VisitReview review : dayReviews) {
            if (review.stars() != null) {
                answered++;
                stars += review.stars();
            }
            if (review.thumb() == VisitReview.Thumb.UP) up++;
            if (review.thumb() == VisitReview.Thumb.DOWN) down++;
        }
        var reviewSummary = new ShiftBriefing.Reviews(answered, answered == 0 ? 0 : (double) stars / answered, up, down);

        return new ShiftBriefing(kind, date, venue, List.copyOf(slots), guests, awaiting, cancelled,
                List.copyOf(lunchBookings), lunchLine(offer), billSummary, reviewSummary, properties.warnings());
    }

    private boolean isLunch(Optional<BusinessLunchOffer> offer, Instant startAt, ZoneId zone) {
        if (offer.isEmpty()) return false;
        BusinessLunchOffer lunch = offer.get();
        LocalDateTime local = LocalDateTime.ofInstant(startAt, zone);
        if (!lunch.days().isEmpty() && !lunch.days().contains(local.getDayOfWeek())) return false;
        LocalTime time = local.toLocalTime();
        return lunch.from() != null && lunch.to() != null
                && !time.isBefore(lunch.from()) && time.isBefore(lunch.to());
    }

    private String lunchLine(Optional<BusinessLunchOffer> offer) {
        if (offer.isEmpty()) return null;
        BusinessLunchOffer lunch = offer.get();
        var line = new StringBuilder();
        if (lunch.from() != null && lunch.to() != null) line.append(lunch.from()).append("–").append(lunch.to());
        if (!lunch.sets().isEmpty()) {
            line.append(line.isEmpty() ? "" : " · ");
            line.append(lunch.sets().size()).append(" сета");
            Integer price = lunch.sets().get(0).priceRub();
            if (price != null) line.append(" от ").append(price).append(" ₽");
        }
        if (!lunch.confirmedByVenue()) line.append(line.isEmpty() ? "" : " · ").append("не подтверждён заведением");
        return line.isEmpty() ? null : line.toString();
    }
}
