package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TableReservationService {

    private final TableReservationRepository repository;
    private final TableReservationNotificationService notificationService;

    public List<VenueTable> listTables(String venueCode) {
        return repository.findTables(venueCode);
    }

    public List<TableAvailability> availability(String venueCode, Instant startAt, Instant endAt, int partySize) {
        validateWindow(startAt, endAt);
        validatePartySize(partySize);
        List<VenueTable> available = repository.findAvailableTables(venueCode, startAt, endAt, partySize);
        return available.stream()
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

    @Transactional
    public TableReservationOrder createReservation(TableReservationCommand command) {
        validateCommand(command);

        VenueTable table = resolveTable(command);
        if (Boolean.FALSE.equals(table.active()) || Boolean.FALSE.equals(table.bookable())) {
            throw conflict("Table is not bookable", table.tableCode());
        }
        if (table.capacityMax() < command.partySize()) {
            throw conflict("Table capacity is lower than requested party size", table.tableCode());
        }
        if (repository.hasActiveConflict(table.id(), command.requestedStartAt(), command.requestedEndAt())) {
            throw conflict("Table already has an active hold for this time window", table.tableCode());
        }

        TableReservationOrder order = repository.createAwaitingManagerOrder(command, table);
        notificationService.notifyHostessApprovalRequest(order);
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
        TableReservationOrder current = requireOrder(id);
        if (current.status() != TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            throw conflict("Only awaiting manager confirmation reservations can be rejected", current.tableCode());
        }

        List<VenueTable> alternatives = alternativesForRejected(current);
        TableReservationOrder rejected = repository.reject(id);
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

        VenueTable table = resolveChangedTable(current, resolved);
        if (Boolean.FALSE.equals(table.active()) || Boolean.FALSE.equals(table.bookable())) {
            throw conflict("Table is not bookable", table.tableCode());
        }
        if (table.capacityMax() < resolved.partySize()) {
            throw conflict("Table capacity is lower than requested party size", table.tableCode());
        }
        if (repository.hasActiveConflict(table.id(), resolved.requestedStartAt(), resolved.requestedEndAt(), current.id())) {
            throw conflict("Table already has an active hold for this time window", table.tableCode());
        }

        TableReservationOrder changed = repository.changeReservation(current.id(), resolved, table);
        notificationService.notifyHostessApprovalRequest(changed);
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

    private VenueTable resolveTable(TableReservationCommand command) {
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
                .findFirst()
                .or(() -> repository.findAvailableTables(
                                command.venueCode(),
                                command.requestedStartAt(),
                                command.requestedEndAt(),
                                command.partySize()
                        )
                        .stream()
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

    private VenueTable resolveChangedTable(TableReservationOrder current, TableReservationChangeCommand command) {
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
                ).stream().findFirst())
                .or(() -> repository.findAvailableTables(
                                command.venueCode(),
                                command.requestedStartAt(),
                                command.requestedEndAt(),
                                command.partySize()
                        )
                        .stream()
                        .findFirst())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCode.CONFLICT,
                        "No available table for requested time window and party size"
                ));
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
