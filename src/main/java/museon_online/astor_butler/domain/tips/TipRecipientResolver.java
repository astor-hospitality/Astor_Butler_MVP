package museon_online.astor_butler.domain.tips;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.tip.StaffProfile;
import museon_online.astor_butler.domain.tip.TipRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Who gets a guest's tip (#161). With the feature on, the waiter assigned to the table of the guest's visit today,
 * provided the waiter has a tip link in the directory; in every other case — no table, no assignment, no link,
 * feature off, directory unreachable — the venue's default recipient from {@code staff_profiles}, exactly as before.
 */
@Service
@Slf4j
public class TipRecipientResolver {

    /** A visit that started this long ago may still be at its table: a long dinner, a banquet. */
    static final Duration VISIT_LENGTH = Duration.ofHours(6);
    /** A guest may come and tip before the time they booked. */
    static final Duration EARLY_ARRIVAL = Duration.ofHours(1);

    private final TipsPersonalProperties properties;
    private final TipsPersonalRepository directory;
    private final TipRepository tips;
    private final TableReservationRepository reservations;
    private final Clock clock;

    @Autowired
    public TipRecipientResolver(TipsPersonalProperties properties, TipsPersonalRepository directory,
                                TipRepository tips, TableReservationRepository reservations) {
        this(properties, directory, tips, reservations, Clock.systemUTC());
    }

    TipRecipientResolver(TipsPersonalProperties properties, TipsPersonalRepository directory,
                         TipRepository tips, TableReservationRepository reservations, Clock clock) {
        this.properties = properties;
        this.directory = directory;
        this.tips = tips;
        this.reservations = reservations;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** The recipient for a tip at the table of this order: its waiter today, else the default. */
    public TipRecipient forOrder(TableReservationOrder order) {
        return forOrder(order, null);
    }

    public TipRecipient forOrder(TableReservationOrder order, String venueCode) {
        if (!properties.isEnabled()) {
            return defaultRecipient(venueCode);
        }
        String table = order == null || order.tableCode() == null || order.tableCode().isBlank() ? null : order.tableCode().trim();
        if (table == null) {
            return defaultRecipient(venueCode);
        }
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        Optional<TipStaffMember> waiter;
        try {
            waiter = directory.waiterOfTable(properties.tenantOrDefault(), table, today);
        } catch (RuntimeException e) {
            log.warn("Waiter of table {} could not be read, the default tip recipient is used: {}", table, e.toString());
            return defaultRecipient(venueCode);
        }
        if (waiter.isEmpty()) {
            log.debug("Table {} has no waiter on {}, the default tip recipient is used", table, today);
            return defaultRecipient(venueCode);
        }
        if (!waiter.get().hasTipLink()) {
            log.info("Waiter {} of table {} has no tip link, the default tip recipient is used", waiter.get().staffId(), table);
            return defaultRecipient(venueCode);
        }
        return TipRecipient.personal(waiter.get());
    }

    /** The recipient for a tip from this chat: by the guest's visit today (started, or about to start). */
    public TipRecipient forChat(Long chatId) {
        return forChat(chatId, null);
    }

    public TipRecipient forChat(Long chatId, String venueCode) {
        if (!properties.isEnabled() || chatId == null) {
            return defaultRecipient(venueCode);
        }
        return currentVisit(chatId)
                .map(order -> forOrder(order, venueCode))
                .orElseGet(() -> defaultRecipient(venueCode));
    }

    /** The venue's recipient from {@code staff_profiles}, the one the tips went to before #161. */
    public TipRecipient defaultRecipient(String venueCode) {
        Optional<StaffProfile> profile;
        try {
            profile = tips.findDefaultStaff(venueCode);
        } catch (RuntimeException e) {
            log.warn("Default tip recipient could not be read: {}", e.toString());
            return TipRecipient.none();
        }
        return profile
                .map(staff -> TipRecipient.venue(staff.displayName(), blankToNull(staff.sbpLink())))
                .orElse(TipRecipient.none());
    }

    /** Today's reservation of the guest: the latest that has started, else one about to start. */
    private Optional<TableReservationOrder> currentVisit(Long chatId) {
        List<TableReservationOrder> active;
        try {
            active = reservations.findActiveOrdersByChatId(chatId);
        } catch (RuntimeException e) {
            log.warn("Reservation for the tip recipient could not be read: chatId={}, reason={}", chatId, e.toString());
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<TableReservationOrder> started = active.stream()
                .filter(reservation -> reservation.requestedStartAt() != null)
                .filter(reservation -> !reservation.requestedStartAt().isAfter(now)
                        && reservation.requestedStartAt().isAfter(now.minus(VISIT_LENGTH)))
                .max(Comparator.comparing(TableReservationOrder::requestedStartAt));
        return started.or(() -> active.stream()
                .filter(reservation -> reservation.requestedStartAt() != null)
                .filter(reservation -> reservation.requestedStartAt().isAfter(now)
                        && !reservation.requestedStartAt().isAfter(now.plus(EARLY_ARRIVAL)))
                .min(Comparator.comparing(TableReservationOrder::requestedStartAt)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
