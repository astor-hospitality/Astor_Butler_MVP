package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.domain.booking.external.ExternalAvailabilityResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import museon_online.astor_butler.domain.booking.external.ExternalTableOccupancy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TableReservationService {

    /** The local order changed after it was written to the restaurant's system, which still has the old data. */
    static final String EXTERNAL_CHANGE_NOT_SYNCED = "EXTERNAL_CHANGE_NOT_SYNCED";
    /** The restaurant's system now holds the changed data of the local order. */
    static final String EXTERNAL_CHANGE_SYNCED = "EXTERNAL_CHANGE_SYNCED";
    private static final String EXTERNALLY_BUSY = "Table is busy in the restaurant booking system";
    /** The orders carry no venue of their own; everything in this service books AERIS, as the change command does. */
    private static final String DEFAULT_VENUE_CODE = "AERIS";

    private final TableReservationRepository repository;
    private final TableReservationNotificationService notificationService;
    private final ExternalReservationProvider externalProvider;

    /** The waiter's guest card at seating (#163): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GuestCardService guestCardService;

    /** The guest journey ledger (spec 2026-10-13): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents;

    /** The useful message at seating (spec 2026-10-13): optional, silent while ASTOR_BUTLER_TALK_ENABLED is off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.talk.SeatedHint seatedHint;

    /** «Вернулся» on a new booking (spec 2026-10-13 §3.5): optional, silent while ASTOR_RETURN_LOOP_ENABLED is off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.ReturnMatcher returnMatcher;

    /** The unified call log (docs/operations/CALL_LOG.md): optional so the constructor and its tests stay as they are. */
    private museon_online.astor_butler.domain.calllog.CallLogWriter callLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCallLog(museon_online.astor_butler.domain.calllog.CallLogWriter callLog) {
        this.callLog = callLog;
    }

    private void callLog(String name, TableReservationOrder order) {
        if (callLog != null && callLog.enabled() && order != null) {
            callLog.targetAction(name, order.status() == null ? null : order.status().name(), order.source(),
                    museon_online.astor_butler.domain.calllog.CallLogWriter.firstId(order.telegramUserId(), order.chatId()));
        }
    }

    public void setJourneyEvents(museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents) {
        this.journeyEvents = journeyEvents;
    }

    private void journey(TableReservationOrder order, museon_online.astor_butler.domain.journey.VisitEventKind kind, String source) {
        if (journeyEvents != null && order != null) {
            journeyEvents.onReservation(order, kind, source);
        }
    }

    public List<VenueTable> listTables(String venueCode) {
        return repository.findTables(venueCode);
    }

    public List<TableAvailability> availability(String venueCode, Instant startAt, Instant endAt, int partySize) {
        validateWindow(startAt, endAt);
        validatePartySize(partySize);
        List<VenueTable> available = repository.findAvailableTables(venueCode, startAt, endAt, partySize);
        ExternalTableOccupancy external = externalOccupancy(venueCode, startAt, endAt, partySize);
        return available.stream()
                .filter(table -> !external.blocks(table.tableCode()))
                .map(TableAvailability::available)
                .toList();
    }

    public TableReservationOrder getReservation(Long id) {
        return requireOrder(id);
    }

    public List<TableReservationOrder> listReservationsByChatId(Long chatId, int limit) {
        if (chatId == null) {
            throw badRequest("chatId is required");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findOrdersByChatId(chatId, safeLimit);
    }

    public List<TableReservationOrder> listActiveReservationsByChatId(Long chatId) {
        if (chatId == null) {
            throw badRequest("chatId is required");
        }
        return repository.findActiveOrdersByChatId(chatId);
    }

    /**
     * The guest's own request at this venue whose time crosses the given window; the earliest one when there are several.
     * One guest cannot sit at two tables at once, so whatever creates a reservation asks here first.
     * Only a request that still holds a table counts: a rejected, cancelled or expired one is not in the way.
     * Touching is not crossing: a visit that ends at 13:00 leaves 13:00 free. An unfinished window crosses nothing.
     */
    public Optional<TableReservationOrder> findOverlappingReservation(Long chatId, String venueCode, Instant startAt, Instant endAt) {
        if (chatId == null) {
            throw badRequest("chatId is required");
        }
        if (startAt == null || endAt == null) {
            return Optional.empty();
        }
        return repository.findActiveOrdersByChatId(chatId, venueCode).stream()
                .filter(TableReservationService::holdsATable)
                .filter(order -> order.requestedStartAt() != null && order.requestedEndAt() != null)
                .filter(order -> order.requestedStartAt().isBefore(endAt) && startAt.isBefore(order.requestedEndAt()))
                .min(Comparator.comparing(TableReservationOrder::requestedStartAt));
    }

    @Transactional
    public TableReservationOrder createReservation(TableReservationCommand command) {
        validateCommand(command);

        ExternalTableOccupancy external = externalOccupancy(
                command.venueCode(), command.requestedStartAt(), command.requestedEndAt(), command.partySize());
        VenueTable table = resolveTable(command, external);
        if (Boolean.FALSE.equals(table.active()) || Boolean.FALSE.equals(table.bookable())) {
            throw conflict("Table is not bookable", table.tableCode());
        }
        if (table.capacityMax() < command.partySize()) {
            throw conflict("Table capacity is lower than requested party size", table.tableCode());
        }
        if (repository.hasActiveConflict(table.id(), command.requestedStartAt(), command.requestedEndAt())) {
            throw conflict("Table already has an active hold for this time window", table.tableCode());
        }
        if (external.blocks(table.tableCode())) {
            throw conflict(EXTERNALLY_BUSY, table.tableCode());
        }

        TableReservationOrder order = repository.createAwaitingManagerOrder(command, table);
        ExternalReservationResult sync = reserveExternally(order, command.venueCode());
        order = rememberExternalId(order, sync);
        notificationService.notifyHostessApprovalRequest(order, sync);
        journey(order, museon_online.astor_butler.domain.journey.VisitEventKind.REQUESTED, museon_online.astor_butler.domain.journey.JourneyEvents.BOT);
        callLog("reservation:create", order);
        if (returnMatcher != null && order != null && order.id() != null) {
            returnMatcher.onNewReservation(order.id(), order.guestPhone());
        }
        return order;
    }

    /** Remembers the number the venue's own system gave this reservation, for example the Saby one. */
    @Transactional
    public TableReservationOrder attachExternalId(Long id, String externalId) {
        requireOrder(id);
        if (externalId == null || externalId.isBlank()) {
            throw badRequest("externalId is required");
        }
        return repository.attachExternalId(id, externalId.trim());
    }

    @Transactional
    public TableReservationOrder confirm(Long id) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() == TableReservationStatus.EXPIRED) {
            return confirmAfterExpiry(current);
        }
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            throw conflict("Only awaiting manager confirmation reservations can be confirmed", current.tableCode());
        }

        TableReservationOrder confirmed = repository.confirm(id);
        notificationService.notifyHostessConfirmed(confirmed);
        notificationService.notifyGuestConfirmed(confirmed);
        callLog("reservation:confirm", confirmed);
        return confirmed;
    }

    /**
     * The manager said «Да» to a request whose hold ran out (#87): the table is taken again for the requested time,
     * if nothing else holds it meanwhile, and the guest gets the order as after an ordinary «Да».
     */
    private TableReservationOrder confirmAfterExpiry(TableReservationOrder current) {
        if (!tableStillFree(current)) {
            throw conflict("Table already has an active hold for this time window", current.tableCode());
        }
        if (!repository.confirmExpired(current.id())) {
            throw conflict("Only awaiting manager confirmation reservations can be confirmed", current.tableCode());
        }
        TableReservationOrder confirmed = requireOrder(current.id());
        notificationService.notifyHostessConfirmed(confirmed);
        notificationService.notifyGuestConfirmed(confirmed);
        callLog("reservation:confirm", confirmed);
        return confirmed;
    }

    /** Whether an expired request could take its table again right now: nothing else holds it for that time. */
    public boolean tableStillFree(TableReservationOrder order) {
        return order != null
                && order.tableId() != null
                && order.requestedStartAt() != null
                && order.requestedEndAt() != null
                && !repository.hasActiveConflict(order.tableId(), order.requestedStartAt(), order.requestedEndAt());
    }

    /**
     * Nobody answered the hostess card within the TTL (#87): the request is EXPIRED, its table is free again, the
     * guest is told and offered another time, the hostess card is closed, the manager gets the card to decide late.
     * A request decided meanwhile, or one the venue's own system already knows, is left alone (conflict), which the
     * clock treats as nothing to do.
     */
    @Transactional
    public TableReservationOrder expireHold(Long id, int afterMinutes) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            throw conflict("Only awaiting manager confirmation reservations can expire", current.tableCode());
        }
        if (hasExternalId(current)) {
            throw conflict("A reservation known to the restaurant system does not expire", current.tableCode());
        }
        if (!repository.expireIfAwaiting(id)) {
            throw conflict("Only awaiting manager confirmation reservations can expire", current.tableCode());
        }

        TableReservationOrder expired = requireOrder(id);
        notificationService.notifyHostessHoldExpired(expired, afterMinutes);
        notificationService.notifyManagerHoldExpired(expired, afterMinutes);
        notificationService.notifyGuestHoldExpired(expired, afterMinutes);
        journey(expired, museon_online.astor_butler.domain.journey.VisitEventKind.CANCELLED, museon_online.astor_butler.domain.journey.JourneyEvents.BOT);
        return expired;
    }

    /**
     * Nobody answered the hostess card within the timeout and the policy says yes: the booking is confirmed as if the
     * hostess had pressed the button, the guest gets the order, the hostess sees why. A card decided meanwhile is left
     * alone (conflict), which the timer treats as nothing to do.
     */
    @Transactional
    public TableReservationOrder confirmAutomatically(Long id, int afterMinutes) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            throw conflict("Only awaiting manager confirmation reservations can be confirmed", current.tableCode());
        }

        TableReservationOrder confirmed = repository.confirm(id);
        notificationService.notifyHostessAutoConfirmed(confirmed, afterMinutes);
        notificationService.notifyGuestConfirmed(confirmed);
        callLog("reservation:confirm", confirmed);
        return confirmed;
    }

    @Transactional
    public TableReservationOrder reject(Long id) {
        return reject(id, true);
    }

    /**
     * The venue accepted the booking in its own system: the local order follows, once. Nothing is written back.
     * A rejected, cancelled or already confirmed order is returned as it is.
     */
    @Transactional
    public TableReservationOrder confirmFromVenue(Long id) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            return current;
        }
        TableReservationOrder confirmed = repository.confirm(id);
        notificationService.notifyHostessConfirmed(confirmed);
        notificationService.notifyGuestConfirmed(confirmed);
        callLog("reservation:confirm", confirmed);
        return confirmed;
    }

    /**
     * The venue dropped the booking in its own system: an awaiting order is rejected, a confirmed one is cancelled,
     * the guest hears about it with alternatives. The venue's system is not asked to cancel what it already cancelled.
     */
    @Transactional
    public TableReservationOrder cancelFromVenue(Long id) {
        TableReservationOrder current = requireOrder(id);
        return switch (current.status()) {
            case AWAITING_MANAGER_CONFIRMATION -> reject(id, false);
            case CONFIRMED, SEATED -> {
                List<VenueTable> alternatives = alternativesForRejected(current);
                TableReservationOrder cancelled = repository.cancel(id);
                notificationService.notifyGuestRejected(cancelled, alternatives);
                journey(cancelled, museon_online.astor_butler.domain.journey.VisitEventKind.CANCELLED, museon_online.astor_butler.domain.journey.JourneyEvents.PRESTO);
                yield cancelled;
            }
            default -> current;
        };
    }

    /**
     * The hostess pressed «🪑 Гость сел» (#151): the confirmed booking becomes SEATED and remembers the time; the
     * table stays held as before. Pressing twice is not an error — the order comes back as it is. A booking that
     * is not confirmed (still awaiting, refused, cancelled, expired) cannot be seated: conflict.
     *
     * @param staffRef who pressed, for the log and the system chat
     */
    @Transactional
    public TableReservationOrder seatByStaff(Long id, String staffRef) {
        return seat(id, staffRef == null || staffRef.isBlank() ? "хостес" : staffRef);
    }

    /**
     * The venue's own system says the guest is at the table (#151, {@code ASTOR_SABY_SEATED_STATES}): a confirmed
     * local order follows, once, and the hostess chat gets a short note. Any other status is returned as it is: an
     * awaiting order is confirmed first by {@link #confirmFromVenue} on its own read, a seated one is not stamped
     * again, a closed one is not reopened.
     */
    @Transactional
    public TableReservationOrder seatFromVenue(Long id) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() != TableReservationStatus.CONFIRMED) {
            return current;
        }
        TableReservationOrder seated = seat(id, "Presto");
        notificationService.notifyHostessSeatedByVenue(seated);
        return seated;
    }

    private TableReservationOrder seat(Long id, String who) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() == TableReservationStatus.SEATED) {
            return current;
        }
        if (current.status() != TableReservationStatus.CONFIRMED) {
            throw conflict("Only confirmed reservations can be seated", current.tableCode());
        }
        if (!repository.seatIfConfirmed(id)) {
            // Seated by the other path between the read and the write: that stamp stands, this one is a repeat.
            TableReservationOrder again = requireOrder(id);
            if (again.status() == TableReservationStatus.SEATED) {
                return again;
            }
            throw conflict("Only confirmed reservations can be seated", current.tableCode());
        }
        TableReservationOrder seated = requireOrder(id);
        log.info("Table reservation {} seated by {}: table {}, seatedAt {}", id, who, seated.tableCode(), seated.seatedAt());
        notificationService.traceSeated(seated, who);
        sendGuestCard(seated);
        if (seatedHint != null) {
            seatedHint.send(seated);
        }
        if (seated.chatId() == null) {
            notificationService.notifyGuestSeated(seated);
        }
        callLog("reservation:seated", seated);
        journey(seated, museon_online.astor_butler.domain.journey.VisitEventKind.SEATED, "Presto".equals(who) ? museon_online.astor_butler.domain.journey.JourneyEvents.PRESTO : museon_online.astor_butler.domain.journey.JourneyEvents.STAFF);
        return seated;
    }

    /**
     * The guest card for the waiter (#163) follows the seating, whichever path seated the guest; a card that cannot be
     * built or sent never undoes the seating. Silent while the feature is off or the service is not wired.
     */
    private void sendGuestCard(TableReservationOrder seated) {
        if (guestCardService == null) {
            return;
        }
        try {
            guestCardService.onSeated(seated);
        } catch (RuntimeException e) {
            log.warn("Guest card was not sent after seating: orderId={}, reason={}", seated.id(), e.toString());
        }
    }

    void setGuestCardService(GuestCardService guestCardService) {
        this.guestCardService = guestCardService;
    }

    private TableReservationOrder reject(Long id, boolean cancelInVenueSystem) {
        TableReservationOrder current = requireOrder(id);
        // An expired request (#87) may still be declined late: the guest then hears the polite no with alternatives.
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                && current.status() != TableReservationStatus.EXPIRED) {
            throw conflict("Only awaiting manager confirmation reservations can be rejected", current.tableCode());
        }

        List<VenueTable> alternatives = alternativesForRejected(current);
        TableReservationOrder rejected = repository.reject(id);
        if (cancelInVenueSystem) {
            cancelExternally(rejected);
        }
        notificationService.notifyGuestRejected(rejected, alternatives);
        journey(rejected, museon_online.astor_butler.domain.journey.VisitEventKind.CANCELLED, cancelInVenueSystem ? museon_online.astor_butler.domain.journey.JourneyEvents.STAFF : museon_online.astor_butler.domain.journey.JourneyEvents.PRESTO);
        return rejected;
    }

    @Transactional
    public TableReservationOrder cancelByGuest(Long id) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() == TableReservationStatus.CANCELLED) {
            return current;
        }
        if (!holdsATable(current)) {
            throw conflict("Only active table reservations can be cancelled", current.tableCode());
        }

        TableReservationOrder cancelled = repository.cancel(id);
        cancelExternally(cancelled);
        notificationService.notifyHostessGuestCancelled(cancelled);
        journey(cancelled, museon_online.astor_butler.domain.journey.VisitEventKind.CANCELLED, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST);
        return cancelled;
    }

    @Transactional
    public TableReservationOrder changeByGuest(Long id, TableReservationChangeCommand command) {
        TableReservationOrder current = requireOrder(id);
        if (!canBeChanged(current)) {
            throw conflict("Only active table reservations can be changed", current.tableCode());
        }
        ValidatedChange change = validateChange(current, command);
        TableReservationChangeCommand resolved = change.command();
        VenueTable table = change.table();

        TableReservationOrder changed = repository.changeReservation(current.id(), resolved, table);
        // A booking already in the restaurant's system is rewritten there; one that never got there is created now.
        ExternalReservationResult sync = hasExternalId(changed)
                ? updateExternally(changed, resolved.venueCode())
                : reserveExternally(changed, resolved.venueCode());
        changed = rememberExternalId(changed, sync);
        notificationService.notifyHostessApprovalRequest(changed, sync);
        journey(changed, museon_online.astor_butler.domain.journey.VisitEventKind.MOVED, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST);
        return changed;
    }

    /**
     * The hostess moves the booking — another table, another time, more or fewer chairs (#150) — with the same checks a
     * guest's change passes: window, party size, capacity, local holds, the restaurant's own occupancy. The order keeps
     * its status (she is the one who confirms), nothing is reopened. The guest hears about it only when the time
     * changed; a new table or a changed party size is her business. The restaurant's system is not rewritten here:
     * a booking that is already in Presto is hers to update there, and the panel says so.
     *
     * @param staffRef who pressed the buttons, for the log and the system chat
     */
    @Transactional
    public TableReservationOrder changeByStaff(Long id, TableReservationChangeCommand command, String staffRef) {
        TableReservationOrder current = requireOrder(id);
        if (!canBeChanged(current)) {
            throw conflict("Only active table reservations can be changed", current.tableCode());
        }
        ValidatedChange change = validateChange(current, command);

        TableReservationOrder changed = repository.changeReservationByStaff(current.id(), change.command(), change.table());
        log.info("Table reservation {} changed by staff {}: {}", id, staffRef, describeChange(current, changed));
        if (timeChanged(current, changed)) {
            notificationService.notifyGuestMovedByStaff(changed, current);
        }
        return changed;
    }

    /**
     * Tables the hostess may move this booking to at its current time and party size: free locally (the booking's own
     * hold left out, so its table is among them) and not busy in the restaurant's system, unless it is this booking
     * that holds the table there.
     */
    public List<VenueTable> tablesFreeFor(TableReservationOrder order) {
        if (order == null || order.requestedStartAt() == null || order.requestedEndAt() == null || order.partySize() == null) {
            return List.of();
        }
        ExternalTableOccupancy external = externalOccupancy(
                DEFAULT_VENUE_CODE, order.requestedStartAt(), order.requestedEndAt(), order.partySize());
        return repository.findAlternativeTables(
                        DEFAULT_VENUE_CODE,
                        order.requestedStartAt(),
                        order.requestedEndAt(),
                        order.partySize(),
                        null,
                        order.id()
                )
                .stream()
                .filter(table -> heldExternallyByThisOrder(order, table) || !external.blocks(table.tableCode()))
                .toList();
    }

    /**
     * Which changes a guest or the hostess may still make: a refused, cancelled or expired request holds nothing;
     * a seated guest (#151) may still be moved by the hostess — another table, more chairs.
     */
    private static boolean canBeChanged(TableReservationOrder order) {
        return holdsATable(order);
    }

    /** Awaiting, confirmed or seated: the statuses that keep a table and count as the guest's live booking. */
    static boolean holdsATable(TableReservationOrder order) {
        return order != null && (order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                || order.status() == TableReservationStatus.CONFIRMED
                || order.status() == TableReservationStatus.SEATED);
    }

    static boolean timeChanged(TableReservationOrder before, TableReservationOrder after) {
        return !java.util.Objects.equals(before.requestedStartAt(), after.requestedStartAt())
                || !java.util.Objects.equals(before.requestedEndAt(), after.requestedEndAt());
    }

    private static String describeChange(TableReservationOrder before, TableReservationOrder after) {
        return "table " + before.tableCode() + " -> " + after.tableCode()
                + ", window " + before.requestedStartAt() + "/" + before.requestedEndAt()
                + " -> " + after.requestedStartAt() + "/" + after.requestedEndAt()
                + ", party " + before.partySize() + " -> " + after.partySize()
                + ", status " + after.status();
    }

    /** A change that passed every check: the filled-in command and the table it lands on. */
    private record ValidatedChange(TableReservationChangeCommand command, VenueTable table) {
    }

    /** The checks every change passes, whoever asks for it: window, party size, capacity, holds, the venue's occupancy. */
    private ValidatedChange validateChange(TableReservationOrder current, TableReservationChangeCommand command) {
        TableReservationChangeCommand resolved = normalizeChangeCommand(current, command);
        validateWindow(resolved.requestedStartAt(), resolved.requestedEndAt());
        validatePartySize(resolved.partySize());

        ExternalTableOccupancy external = externalOccupancy(
                resolved.venueCode(), resolved.requestedStartAt(), resolved.requestedEndAt(), resolved.partySize());
        VenueTable table = resolveChangedTable(current, resolved, external);
        if (Boolean.FALSE.equals(table.active()) || Boolean.FALSE.equals(table.bookable())) {
            throw conflict("Table is not bookable", table.tableCode());
        }
        if (table.capacityMax() < resolved.partySize()) {
            throw conflict("Table capacity is lower than requested party size", table.tableCode());
        }
        if (repository.hasActiveConflict(table.id(), resolved.requestedStartAt(), resolved.requestedEndAt(), current.id())) {
            throw conflict("Table already has an active hold for this time window", table.tableCode());
        }
        if (!heldExternallyByThisOrder(current, table) && external.blocks(table.tableCode())) {
            throw conflict(EXTERNALLY_BUSY, table.tableCode());
        }
        return new ValidatedChange(resolved, table);
    }

    private TableReservationOrder requireOrder(Long id) {
        if (id == null) {
            throw badRequest("reservation id is required");
        }
        return repository.findOrder(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.NOT_FOUND,
                        "Table reservation was not found",
                        Map.of("id", id)
                ));
    }

    private List<VenueTable> alternativesForRejected(TableReservationOrder order) {
        if (order == null || order.requestedStartAt() == null || order.requestedEndAt() == null || order.partySize() == null) {
            return List.of();
        }
        // The refused order's own hold is left out of the search, so its table comes back as free: the hostess just
        // said no to that very table, so it is never one of the alternatives.
        String sameZone = order.preferredZone();
        List<VenueTable> zoneAlternatives = repository.findAlternativeTables(
                        "AERIS",
                        order.requestedStartAt(),
                        order.requestedEndAt(),
                        order.partySize(),
                        sameZone,
                        order.id()
                )
                .stream()
                .filter(table -> !isRefusedTable(order, table))
                .limit(3)
                .toList();
        if (!zoneAlternatives.isEmpty()) {
            return zoneAlternatives;
        }
        return repository.findAlternativeTables(
                        "AERIS",
                        order.requestedStartAt(),
                        order.requestedEndAt(),
                        order.partySize(),
                        null,
                        order.id()
                )
                .stream()
                .filter(table -> !isRefusedTable(order, table))
                .limit(3)
                .toList();
    }

    private static boolean isRefusedTable(TableReservationOrder order, VenueTable table) {
        return (order.tableId() != null && order.tableId().equals(table.id()))
                || (order.tableCode() != null && order.tableCode().equalsIgnoreCase(table.tableCode()));
    }

    private VenueTable resolveTable(TableReservationCommand command, ExternalTableOccupancy external) {
        if (command.tableCode() != null && !command.tableCode().isBlank()) {
            return repository.findTableByCode(command.venueCode(), command.tableCode())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            ErrorCode.NOT_FOUND,
                            "Requested table was not found",
                            Map.of("tableCode", command.tableCode())
                    ));
        }

        return repository.findAvailableTables(
                        command.venueCode(),
                        command.requestedStartAt(),
                        command.requestedEndAt(),
                        command.partySize(),
                        command.preferredZone()
                )
                .stream()
                .filter(table -> !external.blocks(table.tableCode()))
                .findFirst()
                .or(() -> repository.findAvailableTables(
                                command.venueCode(),
                                command.requestedStartAt(),
                                command.requestedEndAt(),
                                command.partySize()
                        )
                        .stream()
                        .filter(table -> !external.blocks(table.tableCode()))
                        .findFirst())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCode.CONFLICT,
                        "No available table for requested time window and party size"
                ));
    }

    private TableReservationChangeCommand normalizeChangeCommand(
            TableReservationOrder current,
            TableReservationChangeCommand command
    ) {
        if (command == null) {
            throw badRequest("Change request body is required");
        }
        return new TableReservationChangeCommand(
                command.venueCode() == null || command.venueCode().isBlank() ? "AERIS" : command.venueCode(),
                blankToNull(command.tableCode()),
                blankToNull(command.preferredZone()) == null ? current.preferredZone() : command.preferredZone(),
                blankToNull(command.seatingPreference()) == null ? current.seatingPreference() : command.seatingPreference(),
                command.requestedStartAt() == null ? current.requestedStartAt() : command.requestedStartAt(),
                command.requestedEndAt() == null ? current.requestedEndAt() : command.requestedEndAt(),
                command.partySize() == null ? current.partySize() : command.partySize(),
                blankToNull(command.guestComment()) == null ? current.guestComment() : command.guestComment()
        );
    }

    private VenueTable resolveChangedTable(
            TableReservationOrder current,
            TableReservationChangeCommand command,
            ExternalTableOccupancy external
    ) {
        if (command.tableCode() != null && !command.tableCode().isBlank()) {
            return repository.findTableByCode(command.venueCode(), command.tableCode())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            ErrorCode.NOT_FOUND,
                            "Requested table was not found",
                            Map.of("tableCode", command.tableCode())
                    ));
        }

        return repository.findTableByCode(command.venueCode(), current.tableCode())
                .filter(table -> table.capacityMax() >= command.partySize()
                        && !repository.hasActiveConflict(
                        table.id(),
                        command.requestedStartAt(),
                        command.requestedEndAt(),
                        current.id()
                ))
                .or(() -> repository.findAvailableTables(
                        command.venueCode(),
                        command.requestedStartAt(),
                        command.requestedEndAt(),
                        command.partySize(),
                        command.preferredZone()
                ).stream().filter(table -> !external.blocks(table.tableCode())).findFirst())
                .or(() -> repository.findAvailableTables(
                                command.venueCode(),
                                command.requestedStartAt(),
                                command.requestedEndAt(),
                                command.partySize()
                        )
                        .stream()
                        .filter(table -> !external.blocks(table.tableCode()))
                        .findFirst())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCode.CONFLICT,
                        "No available table for requested time window and party size"
                ));
    }

    /** What the restaurant's own system says about tables for this time; blocks nothing when it gives no answer. */
    private ExternalTableOccupancy externalOccupancy(String venueCode, Instant startAt, Instant endAt, Integer partySize) {
        ExternalAvailabilityResult result = externalProvider.checkAvailability(
                new ExternalReservationProvider.ExternalAvailabilityRequest(venueCode, startAt, endAt, partySize, null, null));
        ExternalTableOccupancy occupancy = ExternalTableOccupancy.from(
                result,
                () -> repository.findTables(venueCode).stream().map(VenueTable::tableCode).toList()
        );
        if (result.providerConfigured() && !occupancy.authoritative()) {
            log.warn("External table occupancy is not used, local availability decides: provider={}, reason={}",
                    result.providerId(), occupancy.reason());
        }
        return occupancy;
    }

    /** Writes the stored order to the restaurant's system; the order id is the duplicate guard and the marker in its comment. */
    private ExternalReservationResult reserveExternally(TableReservationOrder order, String venueCode) {
        return externalProvider.reserve(commandOf(order, venueCode), String.valueOf(order.id()));
    }

    /** Rewrites the booking in the restaurant's system; when that is not certain, the hostess is asked to look. */
    private ExternalReservationResult updateExternally(TableReservationOrder order, String venueCode) {
        ExternalReservationResult result;
        try {
            result = externalProvider.updateReservation(order.sbisExternalId(), commandOf(order, venueCode), String.valueOf(order.id()));
        } catch (RuntimeException e) {
            log.warn("External booking update failed for order {}: {}", order.id(), e.toString());
            return changeNotSynced(order);
        }
        if (result == null || !result.created()) {
            return changeNotSynced(order);
        }
        return new ExternalReservationResult(true, true, externalProvider.providerId(), EXTERNAL_CHANGE_SYNCED,
                order.sbisExternalId(), "The booking in the restaurant system now has the changed data.", List.of(), result.metadata());
    }

    private TableReservationCommand commandOf(TableReservationOrder order, String venueCode) {
        return new TableReservationCommand(
                order.chatId(),
                order.telegramUserId(),
                order.userId(),
                venueCode,
                order.tableCode(),
                order.preferredZone(),
                order.seatingPreference(),
                order.requestedStartAt(),
                order.requestedEndAt(),
                order.partySize(),
                order.guestName(),
                order.guestPhone(),
                order.guestComment(),
                order.managerTelegramId(),
                order.hostessChatId()
        );
    }

    /**
     * Stores the booking id of the restaurant's system on the local order. If that write fails the
     * external booking is cancelled, so a rolled-back order does not leave a booking behind.
     */
    private TableReservationOrder rememberExternalId(TableReservationOrder order, ExternalReservationResult sync) {
        String externalId = sync.externalReservationId();
        if (!sync.created() || externalId == null || externalId.isBlank() || externalId.equals(order.sbisExternalId())) {
            return order;
        }
        try {
            return repository.attachExternalId(order.id(), externalId);
        } catch (RuntimeException e) {
            externalProvider.cancelReservation(externalId);
            throw e;
        }
    }

    private void cancelExternally(TableReservationOrder order) {
        if (hasExternalId(order) && !externalProvider.cancelReservation(order.sbisExternalId())) {
            notificationService.notifyHostessExternalCancelFailed(order);
        }
    }

    private ExternalReservationResult changeNotSynced(TableReservationOrder order) {
        return new ExternalReservationResult(
                false,
                true,
                externalProvider.providerId(),
                EXTERNAL_CHANGE_NOT_SYNCED,
                order.sbisExternalId(),
                "The booking in the restaurant system still has the data from before the change.",
                List.of(),
                Map.of()
        );
    }

    private boolean hasExternalId(TableReservationOrder order) {
        return order.sbisExternalId() != null && !order.sbisExternalId().isBlank();
    }

    /** The restaurant's system shows this table as busy because of the very booking being changed. */
    private boolean heldExternallyByThisOrder(TableReservationOrder current, VenueTable table) {
        return hasExternalId(current)
                && current.tableCode() != null
                && current.tableCode().equalsIgnoreCase(table.tableCode());
    }

    private void validateCommand(TableReservationCommand command) {
        if (command == null) {
            throw badRequest("Request body is required");
        }
        // A booking from the Concierge's chat has no chat of the Butler's bot; one from the bot always has.
        if (command.chatId() == null && !command.concierge()) {
            throw badRequest("chatId is required");
        }
        validateWindow(command.requestedStartAt(), command.requestedEndAt());
        validatePartySize(command.partySize());
    }

    private void validateWindow(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null) {
            throw badRequest("requestedStartAt and requestedEndAt are required");
        }
        if (!endAt.isAfter(startAt)) {
            throw badRequest("requestedEndAt must be after requestedStartAt");
        }
    }

    private void validatePartySize(Integer partySize) {
        if (partySize == null || partySize < 1) {
            throw badRequest("partySize must be positive");
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    private ApiException conflict(String message, String tableCode) {
        return new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.CONFLICT,
                message,
                Map.of("tableCode", tableCode)
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
