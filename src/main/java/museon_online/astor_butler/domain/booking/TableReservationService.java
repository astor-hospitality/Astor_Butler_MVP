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

    private final TableReservationRepository repository;
    private final TableReservationNotificationService notificationService;
    private final ExternalReservationProvider externalProvider;

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
                .filter(order -> order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                        || order.status() == TableReservationStatus.CONFIRMED)
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
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            throw conflict("Only awaiting manager confirmation reservations can be confirmed", current.tableCode());
        }

        TableReservationOrder confirmed = repository.confirm(id);
        notificationService.notifyHostessConfirmed(confirmed);
        notificationService.notifyGuestConfirmed(confirmed);
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
            case CONFIRMED -> {
                List<VenueTable> alternatives = alternativesForRejected(current);
                TableReservationOrder cancelled = repository.cancel(id);
                notificationService.notifyGuestRejected(cancelled, alternatives);
                yield cancelled;
            }
            default -> current;
        };
    }

    private TableReservationOrder reject(Long id, boolean cancelInVenueSystem) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            throw conflict("Only awaiting manager confirmation reservations can be rejected", current.tableCode());
        }

        List<VenueTable> alternatives = alternativesForRejected(current);
        TableReservationOrder rejected = repository.reject(id);
        if (cancelInVenueSystem) {
            cancelExternally(rejected);
        }
        notificationService.notifyGuestRejected(rejected, alternatives);
        return rejected;
    }

    @Transactional
    public TableReservationOrder cancelByGuest(Long id) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() == TableReservationStatus.CANCELLED) {
            return current;
        }
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                && current.status() != TableReservationStatus.CONFIRMED) {
            throw conflict("Only active table reservations can be cancelled", current.tableCode());
        }

        TableReservationOrder cancelled = repository.cancel(id);
        cancelExternally(cancelled);
        notificationService.notifyHostessGuestCancelled(cancelled);
        return cancelled;
    }

    @Transactional
    public TableReservationOrder changeByGuest(Long id, TableReservationChangeCommand command) {
        TableReservationOrder current = requireOrder(id);
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION
                && current.status() != TableReservationStatus.CONFIRMED) {
            throw conflict("Only active table reservations can be changed", current.tableCode());
        }
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

        TableReservationOrder changed = repository.changeReservation(current.id(), resolved, table);
        // A booking already in the restaurant's system is rewritten there; one that never got there is created now.
        ExternalReservationResult sync = hasExternalId(changed)
                ? updateExternally(changed, resolved.venueCode())
                : reserveExternally(changed, resolved.venueCode());
        changed = rememberExternalId(changed, sync);
        notificationService.notifyHostessApprovalRequest(changed, sync);
        return changed;
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
        String sameZone = order.preferredZone();
        List<VenueTable> zoneAlternatives = repository.findAlternativeTables(
                "AERIS",
                order.requestedStartAt(),
                order.requestedEndAt(),
                order.partySize(),
                sameZone,
                order.id()
        );
        if (!zoneAlternatives.isEmpty()) {
            return zoneAlternatives.stream().limit(3).toList();
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
                .limit(3)
                .toList();
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
        if (command.chatId() == null) {
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
