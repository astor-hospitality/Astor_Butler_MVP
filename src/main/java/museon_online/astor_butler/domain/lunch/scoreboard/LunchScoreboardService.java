package museon_online.astor_butler.domain.lunch.scoreboard;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillLine;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.billing.VisitReview;
import museon_online.astor_butler.domain.billing.VisitReviewRepository;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.shift.ShiftBriefingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the {@link LunchScoreboard} of one day from the tables Butler already has; no new table, no migration.
 *
 * <p>The join: a lunch reservation of the day (marked «Бизнес-ланч», starting that day by the venue clock) → its bill
 * ({@code guest_bills}, one {@code BUSINESS_LUNCH} bill per reservation) → the bill's lines ({@code guest_bill_lines}:
 * what was ordered) and the bill's review ({@code visit_reviews}, one per bill). So a review is tied to the dishes of
 * the same visit, which is as close to «оценка блюда» as the data gets. The guest's wishes (#165) ride in the hostess
 * comment of the reservation as «Пожелание гостя: … — согласовать с кухней» and are cut out of it again here.
 */
@Service
@Slf4j
public class LunchScoreboardService {

    static final int LIMIT = 500;

    private final TableReservationRepository reservations;
    private final GuestBillRepository bills;
    private final VisitReviewRepository reviews;
    private final BusinessLunchCatalog catalog;
    private final ShiftBriefingProperties venue;
    private final boolean billingEnabled;

    public LunchScoreboardService(TableReservationRepository reservations, GuestBillRepository bills,
                                  VisitReviewRepository reviews, BusinessLunchCatalog catalog,
                                  ShiftBriefingProperties venue,
                                  @Value("${astor.billing.enabled:false}") boolean billingEnabled) {
        this.reservations = reservations;
        this.bills = bills;
        this.reviews = reviews;
        this.catalog = catalog;
        this.venue = venue;
        this.billingEnabled = billingEnabled;
    }

    public LunchScoreboard build(LocalDate date) {
        ZoneId zone = venue.zone();
        String venueCode = venue.getVenueCode();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        Optional<BusinessLunchOffer> offer = catalog.find(venueCode);

        List<TableReservationOrder> lunches = reservations.findOrdersStartingBetween(venueCode, from, to, LIMIT).stream()
                .filter(LunchScoreboardService::isLunch)
                .filter(order -> alive(order.status()))
                .toList();
        int guests = lunches.stream().mapToInt(order -> order.partySize() == null ? 0 : order.partySize()).sum();
        List<String> wishes = lunches.stream()
                .map(order -> wishOf(order.guestComment()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (!billingEnabled) {
            return new LunchScoreboard(date, venueCode, lunches.size(), guests, 0, LunchScoreboard.Reviews.NONE, List.of(), wishes, false);
        }

        int visits = 0, answered = 0, stars = 0, five = 0, up = 0, down = 0;
        Map<String, DishTally> tallies = new LinkedHashMap<>();
        for (TableReservationOrder order : lunches) {
            Optional<GuestBill> bill = bills.findByReservation(GuestBillKind.BUSINESS_LUNCH, order.id());
            if (bill.isEmpty() || bill.get().status() == GuestBillStatus.CANCELLED) {
                continue;
            }
            visits++;
            Integer visitStars = null;
            Optional<VisitReview> review = reviews.findByBill(bill.get().id());
            if (review.isPresent()) {
                VisitReview r = review.get();
                if (r.stars() != null) {
                    visitStars = r.stars();
                    answered++;
                    stars += r.stars();
                    if (r.stars() == 5) five++;
                }
                if (r.thumb() == VisitReview.Thumb.UP) up++;
                if (r.thumb() == VisitReview.Thumb.DOWN) down++;
            }
            List<GuestBillLine> lines = bills.findLines(bill.get().id());
            boolean setOrder = lines.stream().anyMatch(GuestBillLine::included);
            for (GuestBillLine line : lines) {
                if (!isDish(line, setOrder, offer)) {
                    continue;
                }
                String key = line.itemCode() == null || line.itemCode().isBlank()
                        ? "title:" + line.title().trim().toLowerCase(Locale.ROOT)
                        : line.itemCode();
                DishTally tally = tallies.computeIfAbsent(key, k -> new DishTally(line.itemCode(), line.title()));
                tally.portions += Math.max(0, line.quantity());
                if (visitStars != null) {
                    tally.reviews++;
                    tally.stars += visitStars;
                }
            }
        }

        List<LunchScoreboard.Dish> dishes = new ArrayList<>();
        for (DishTally tally : tallies.values()) {
            dishes.add(new LunchScoreboard.Dish(tally.code, tally.title, tally.portions, tally.reviews,
                    tally.reviews == 0 ? 0 : (double) tally.stars / tally.reviews));
        }
        dishes.sort(Comparator.comparingInt(LunchScoreboard.Dish::portions).reversed()
                .thenComparing(LunchScoreboard.Dish::title, String.CASE_INSENSITIVE_ORDER));

        var summary = new LunchScoreboard.Reviews(answered, answered == 0 ? 0 : (double) stars / answered, five, up, down);
        return new LunchScoreboard(date, venueCode, lunches.size(), guests, visits, summary, dishes, wishes, true);
    }

    /** The same mark {@code BusinessLunchService} puts on a lunch reservation. */
    static boolean isLunch(TableReservationOrder order) {
        return BusinessLunchService.SEATING_LABEL.equals(order.seatingPreference())
                || order.guestComment() != null && order.guestComment().startsWith(BusinessLunchService.SEATING_LABEL);
    }

    private static boolean alive(TableReservationStatus status) {
        return status == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                || status == TableReservationStatus.CONFIRMED
                || status == TableReservationStatus.SEATED;
    }

    /**
     * A set order carries the set itself as a priced line and its dishes as included ones; the set line is not a dish.
     * The menu file, when it knows the venue, has the last word: a code it lists as a set is never a dish.
     */
    private static boolean isDish(GuestBillLine line, boolean setOrder, Optional<BusinessLunchOffer> offer) {
        if (line.title() == null || line.title().isBlank()) {
            return false;
        }
        if (setOrder && !line.included()) {
            return false;
        }
        return offer.isEmpty() || line.itemCode() == null || offer.get().set(line.itemCode()).isEmpty();
    }

    /** «… Пожелание гостя: без лука — согласовать с кухней. …» → «без лука»; empty when the comment has no wish. */
    static Optional<String> wishOf(String comment) {
        if (comment == null) {
            return Optional.empty();
        }
        int start = comment.indexOf(BusinessLunchService.WISH_PREFIX);
        if (start < 0) {
            return Optional.empty();
        }
        start += BusinessLunchService.WISH_PREFIX.length();
        int end = comment.indexOf(BusinessLunchService.WISH_SUFFIX, start);
        String wish = (end < 0 ? comment.substring(start) : comment.substring(start, end)).trim();
        return wish.isEmpty() ? Optional.empty() : Optional.of(wish);
    }

    private static final class DishTally {
        final String code;
        final String title;
        int portions;
        int reviews;
        int stars;

        DishTally(String code, String title) {
            this.code = code;
            this.title = title == null ? "" : title.trim();
        }
    }
}
