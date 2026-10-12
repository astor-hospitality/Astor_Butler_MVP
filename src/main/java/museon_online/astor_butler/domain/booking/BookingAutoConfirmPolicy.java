package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Which unanswered hostess cards confirm themselves and which have to wait for a person. The same answer is given to
 * the card when it is sent (so the hostess reads on it what will happen) and to the timer when the time is up.
 */
@Service
@RequiredArgsConstructor
public class BookingAutoConfirmPolicy {

    private static final String DEFAULT_VENUE = "AERIS";

    private final BookingAutoConfirmProperties properties;
    private final BookingHostessStateStorage storage;
    private final TableReservationRepository repository;

    @Value("${astor.booking.confirmation-source:HOSTESS}")
    private BookingConfirmationSource confirmationSource = BookingConfirmationSource.HOSTESS;

    public enum Decision {
        /** The feature is off: the card waits for a button for as long as it takes. */
        OFF,
        /** Confirms itself after the timeout. */
        AUTO,
        /** The venue said "overloaded": goes to the manager after the timeout. */
        MANUAL_MODE,
        /** Too many guests for an automatic yes: goes to the manager after the timeout. */
        LARGE_PARTY,
        /** A wish or a table in a zone a person has to see: goes to the manager after the timeout. */
        EXCLUDED_ZONE,
        /** The venue's own system confirms this one; the timer leaves it alone. */
        VENUE_SYSTEM;

        public boolean goesToTheManager() {
            return this == MANUAL_MODE || this == LARGE_PARTY || this == EXCLUDED_ZONE;
        }
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public int timeoutMinutes() {
        return Math.max(1, properties.getTimeoutMinutes());
    }

    public Decision decide(TableReservationOrder order) {
        if (!properties.isEnabled() || order == null) {
            return Decision.OFF;
        }
        if (confirmationSource == BookingConfirmationSource.VENUE_SYSTEM
                && order.sbisExternalId() != null
                && !order.sbisExternalId().isBlank()) {
            return Decision.VENUE_SYSTEM;
        }
        if (storage.manualMode().manual()) {
            return Decision.MANUAL_MODE;
        }
        if (order.partySize() != null && properties.getMaxParty() > 0 && order.partySize() >= properties.getMaxParty()) {
            return Decision.LARGE_PARTY;
        }
        if (inExcludedZone(order)) {
            return Decision.EXCLUDED_ZONE;
        }
        return Decision.AUTO;
    }

    void setConfirmationSource(BookingConfirmationSource confirmationSource) {
        this.confirmationSource = confirmationSource;
    }

    private boolean inExcludedZone(TableReservationOrder order) {
        List<String> zones = excludedZones();
        if (zones.isEmpty()) {
            return false;
        }
        if (order.preferredZone() != null && zones.contains(normalize(order.preferredZone()))) {
            return true;
        }
        if (order.tableCode() == null || order.tableCode().isBlank()) {
            return false;
        }
        return repository.findTableByCode(DEFAULT_VENUE, order.tableCode())
                .map(VenueTable::zone)
                .map(zone -> zones.contains(normalize(zone)))
                .orElse(false);
    }

    private List<String> excludedZones() {
        List<String> configured = properties.getExcludeZones();
        if (configured == null) {
            return List.of();
        }
        return configured.stream()
                .filter(zone -> zone != null && !zone.isBlank())
                .map(BookingAutoConfirmPolicy::normalize)
                .toList();
    }

    private static String normalize(String zone) {
        return zone.trim().toUpperCase(Locale.ROOT);
    }
}
