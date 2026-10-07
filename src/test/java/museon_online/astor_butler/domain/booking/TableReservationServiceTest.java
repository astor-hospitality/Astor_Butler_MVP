package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.external.ExternalAvailabilityResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;

class TableReservationServiceTest {

    private final TableReservationRepository repository = mock(TableReservationRepository.class);
    private final TableReservationNotificationService notificationService = mock(TableReservationNotificationService.class);
    private final ExternalReservationProvider externalProvider = mock(ExternalReservationProvider.class);
    private final TableReservationService service =
            new TableReservationService(repository, notificationService, externalProvider);

    @BeforeEach
    void restaurantSystemIsSwitchedOffByDefault() {
        when(externalProvider.providerId()).thenReturn("SABY");
        when(externalProvider.checkAvailability(any()))
                .thenReturn(ExternalAvailabilityResult.unavailableBecauseUnconfigured("SABY", List.of()));
        when(externalProvider.reserve(any(), any()))
                .thenReturn(ExternalReservationResult.rejectedBecauseUnconfigured("SABY", List.of()));
    }

    @Test
    void createsReservationWhenTableIsAvailable() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(1L, "5", 4, true, true);
        TableReservationCommand command = command("5", start, end, 3);
        TableReservationOrder expected = order(10L, table);

        when(repository.findTableByCode("AERIS", "5")).thenReturn(Optional.of(table));
        when(repository.hasActiveConflict(1L, start, end)).thenReturn(false);
        when(repository.createAwaitingManagerOrder(command, table)).thenReturn(expected);

        TableReservationOrder result = service.createReservation(command);

        assertThat(result).isEqualTo(expected);
        verify(repository).createAwaitingManagerOrder(command, table);
        verify(notificationService).notifyHostessApprovalRequest(eq(expected), any());
        verify(repository, never()).attachExternalId(any(), any());
    }

    @Test
    void writesTheStoredOrderToTheRestaurantSystemAndKeepsItsId() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationCommand command = command(null, start, end, 3);
        TableReservationOrder local = order(10L, table);
        TableReservationOrder linked = withExternalId(local, "saby-1");
        ExternalReservationResult created = externalResult(true, "SABY_ORDER_CREATED_UNCONFIRMED", "saby-1");

        when(externalProvider.checkAvailability(any())).thenReturn(externalAnswer("AVAILABLE", "Стол 5"));
        when(externalProvider.reserve(any(), eq("10"))).thenReturn(created);
        when(repository.findTables("AERIS")).thenReturn(List.of(table));
        when(repository.findAvailableTables("AERIS", start, end, 3)).thenReturn(List.of(table));
        when(repository.createAwaitingManagerOrder(command, table)).thenReturn(local);
        when(repository.attachExternalId(10L, "saby-1")).thenReturn(linked);

        TableReservationOrder result = service.createReservation(command);

        var sent = forClass(TableReservationCommand.class);
        verify(externalProvider).reserve(sent.capture(), eq("10"));
        // The provider gets the table that was chosen and the phone as stored, not the guest's raw request.
        assertThat(sent.getValue().tableCode()).isEqualTo("5");
        assertThat(sent.getValue().venueCode()).isEqualTo("AERIS");
        assertThat(sent.getValue().requestedStartAt()).isEqualTo(local.requestedStartAt());
        assertThat(sent.getValue().partySize()).isEqualTo(3);
        assertThat(sent.getValue().guestName()).isEqualTo("Наталья");
        assertThat(sent.getValue().guestPhone()).isEqualTo("+79990000000");
        assertThat(result.sbisExternalId()).isEqualTo("saby-1");
        verify(notificationService).notifyHostessApprovalRequest(linked, created);
    }

    @Test
    void refusesATableTheRestaurantSystemDoesNotOffer() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(5L, "5", 4, true, true);

        when(externalProvider.checkAvailability(any())).thenReturn(externalAnswer("AVAILABLE", "6"));
        when(repository.findTables("AERIS")).thenReturn(List.of(table, table(6L, "6", 4, true, true)));
        when(repository.findTableByCode("AERIS", "5")).thenReturn(Optional.of(table));

        assertThatThrownBy(() -> service.createReservation(command("5", start, end, 3)))
                .isInstanceOf(ApiException.class)
                .hasMessage("Table is busy in the restaurant booking system");
        verify(repository, never()).createAwaitingManagerOrder(any(), any());
        verify(externalProvider, never()).reserve(any(), any());
    }

    @Test
    void offersAndAutoSelectsOnlyTablesTheRestaurantSystemOffers() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable busyInSaby = table(17L, "17", 2, true, true);
        VenueTable free = table(18L, "18", 2, true, true);
        TableReservationCommand command = command(null, start, end, 2);

        when(externalProvider.checkAvailability(any())).thenReturn(externalAnswer("AVAILABLE", "18"));
        when(repository.findTables("AERIS")).thenReturn(List.of(busyInSaby, free));
        when(repository.findAvailableTables("AERIS", start, end, 2)).thenReturn(List.of(busyInSaby, free));
        when(repository.createAwaitingManagerOrder(command, free)).thenReturn(order(11L, free));

        TableReservationOrder result = service.createReservation(command);
        List<TableAvailability> offered = service.availability("AERIS", start, end, 2);

        assertThat(result.tableCode()).isEqualTo("18");
        assertThat(offered).hasSize(1);
        assertThat(offered.get(0).table().tableCode()).isEqualTo("18");
    }

    @Test
    void aFullRestaurantSystemLeavesNoTableToBook() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(17L, "17", 2, true, true);

        when(externalProvider.checkAvailability(any())).thenReturn(externalAnswer("NO_TABLES_AVAILABLE"));
        when(repository.findAvailableTables("AERIS", start, end, 2)).thenReturn(List.of(table));

        assertThat(service.availability("AERIS", start, end, 2)).isEmpty();
        assertThatThrownBy(() -> service.createReservation(command(null, start, end, 2)))
                .isInstanceOf(ApiException.class)
                .hasMessage("No available table for requested time window and party size");
    }

    @Test
    void anUnreachableRestaurantSystemDoesNotStopTheLocalFlow() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationCommand command = command("5", start, end, 3);
        TableReservationOrder local = order(10L, table);
        ExternalReservationResult unknown = externalResult(false, "PROVIDER_RESULT_UNKNOWN", "");

        when(externalProvider.checkAvailability(any())).thenReturn(
                new ExternalAvailabilityResult(false, true, "SABY", "PROVIDER_TIMEOUT", "", List.of(), Map.of()));
        when(externalProvider.reserve(any(), any())).thenReturn(unknown);
        when(repository.findTableByCode("AERIS", "5")).thenReturn(Optional.of(table));
        when(repository.createAwaitingManagerOrder(command, table)).thenReturn(local);

        TableReservationOrder result = service.createReservation(command);

        assertThat(result).isEqualTo(local);
        verify(repository, never()).attachExternalId(any(), any());
        verify(notificationService).notifyHostessApprovalRequest(local, unknown);
    }

    @Test
    void cancelsTheExternalBookingWhenItsIdCannotBeStored() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationCommand command = command("5", start, end, 3);

        when(externalProvider.reserve(any(), any()))
                .thenReturn(externalResult(true, "SABY_ORDER_CREATED_UNCONFIRMED", "saby-1"));
        when(repository.findTableByCode("AERIS", "5")).thenReturn(Optional.of(table));
        when(repository.createAwaitingManagerOrder(command, table)).thenReturn(order(10L, table));
        when(repository.attachExternalId(10L, "saby-1")).thenThrow(new IllegalStateException("database is down"));

        assertThatThrownBy(() -> service.createReservation(command))
                .isInstanceOf(IllegalStateException.class);
        verify(externalProvider).cancelReservation("saby-1");
    }

    @Test
    void rejectsReservationWhenTimeWindowConflicts() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(1L, "5", 4, true, true);

        when(repository.findTableByCode("AERIS", "5")).thenReturn(Optional.of(table));
        when(repository.hasActiveConflict(1L, start, end)).thenReturn(true);

        assertThatThrownBy(() -> service.createReservation(command("5", start, end, 3)))
                .isInstanceOf(ApiException.class)
                .hasMessage("Table already has an active hold for this time window");
    }

    @Test
    void autoSelectsSmallestAvailableTableWhenTableCodeIsBlank() {
        Instant start = Instant.parse("2026-06-06T17:00:00Z");
        Instant end = Instant.parse("2026-06-06T19:00:00Z");
        VenueTable table = table(17L, "17", 2, true, true);
        TableReservationCommand command = command(null, start, end, 2);
        TableReservationOrder expected = order(11L, table);

        when(repository.findAvailableTables("AERIS", start, end, 2)).thenReturn(List.of(table));
        when(repository.hasActiveConflict(17L, start, end)).thenReturn(false);
        when(repository.createAwaitingManagerOrder(command, table)).thenReturn(expected);

        TableReservationOrder result = service.createReservation(command);

        assertThat(result.tableCode()).isEqualTo("17");
    }

    @Test
    void returnsReservationById() {
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationOrder expected = order(12L, table);

        when(repository.findOrder(12L)).thenReturn(Optional.of(expected));

        TableReservationOrder result = service.getReservation(12L);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void listsReservationsByChatIdWithBoundedLimit() {
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationOrder expected = order(12L, table);
        when(repository.findOrdersByChatId(1773317437L, 100)).thenReturn(List.of(expected));

        List<TableReservationOrder> result = service.listReservationsByChatId(1773317437L, 500);

        assertThat(result).containsExactly(expected);
        verify(repository).findOrdersByChatId(1773317437L, 100);
    }

    @Test
    void listsActiveReservationsByChatId() {
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationOrder expected = order(12L, table, TableReservationStatus.CONFIRMED);
        when(repository.findActiveOrdersByChatId(1773317437L)).thenReturn(List.of(expected));

        List<TableReservationOrder> result = service.listActiveReservationsByChatId(1773317437L);

        assertThat(result).containsExactly(expected);
    }

    @Test
    void confirmsReservationAndNotifiesHostess() {
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationOrder awaiting = order(12L, table, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
        TableReservationOrder confirmed = order(12L, table, TableReservationStatus.CONFIRMED);

        when(repository.findOrder(12L)).thenReturn(Optional.of(awaiting));
        when(repository.confirm(12L)).thenReturn(confirmed);

        TableReservationOrder result = service.confirm(12L);

        assertThat(result.status()).isEqualTo(TableReservationStatus.CONFIRMED);
        verify(notificationService).notifyHostessConfirmed(confirmed);
    }

    @Test
    void changesReservationAndReopensHostessConfirmation() {
        VenueTable currentTable = table(5L, "5", 4, true, true);
        VenueTable betterFit = table(11L, "11", 6, true, true);
        TableReservationOrder current = order(12L, currentTable, TableReservationStatus.CONFIRMED);
        TableReservationOrder changed = new TableReservationOrder(
                12L,
                1773317437L,
                1773317437L,
                null,
                betterFit.id(),
                betterFit.tableCode(),
                betterFit.displayName(),
                null,
                "Хочу спокойный стол",
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                "TELEGRAM",
                Instant.parse("2026-06-06T17:00:00Z"),
                Instant.parse("2026-06-06T19:00:00Z"),
                5,
                "Наталья",
                "+79990000000",
                "Хочу спокойный стол | Количество гостей изменено: 5",
                876857557L,
                null,
                null,
                null,
                Instant.parse("2026-06-05T00:00:00Z"),
                Instant.parse("2026-06-05T00:00:00Z")
        );
        TableReservationChangeCommand command = new TableReservationChangeCommand(
                "AERIS",
                null,
                null,
                null,
                current.requestedStartAt(),
                current.requestedEndAt(),
                5,
                "Хочу спокойный стол | Количество гостей изменено: 5"
        );

        when(repository.findOrder(12L)).thenReturn(Optional.of(current));
        when(repository.findTableByCode("AERIS", "5")).thenReturn(Optional.of(currentTable));
        when(repository.findAvailableTables("AERIS", current.requestedStartAt(), current.requestedEndAt(), 5, null))
                .thenReturn(List.of(betterFit));
        when(repository.hasActiveConflict(11L, current.requestedStartAt(), current.requestedEndAt(), 12L)).thenReturn(false);
        when(repository.changeReservation(eq(12L), any(TableReservationChangeCommand.class), eq(betterFit))).thenReturn(changed);

        TableReservationOrder result = service.changeByGuest(12L, command);

        var captor = forClass(TableReservationChangeCommand.class);
        assertThat(result.status()).isEqualTo(TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
        assertThat(result.tableCode()).isEqualTo("11");
        assertThat(result.partySize()).isEqualTo(5);
        verify(repository).changeReservation(eq(12L), captor.capture(), eq(betterFit));
        assertThat(captor.getValue().partySize()).isEqualTo(5);
        assertThat(captor.getValue().seatingPreference()).isEqualTo("Хочу спокойный стол");
        verify(notificationService).notifyHostessApprovalRequest(eq(changed), any());
        verify(externalProvider).reserve(any(), eq("12"));
    }

    @Test
    void aChangedOrderThatIsAlreadyInTheRestaurantSystemIsLeftForTheHostessToFixThere() {
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationOrder current = withExternalId(order(12L, table, TableReservationStatus.CONFIRMED), "saby-1");
        TableReservationOrder changed = withExternalId(order(12L, table), "saby-1");
        Instant newStart = Instant.parse("2026-06-06T17:30:00Z");
        Instant newEnd = Instant.parse("2026-06-06T19:30:00Z");
        TableReservationChangeCommand command = new TableReservationChangeCommand(
                "AERIS", null, null, null, newStart, newEnd, 3, null);

        // The restaurant system offers only table 6: table 5 is taken there by this very booking.
        when(externalProvider.checkAvailability(any())).thenReturn(externalAnswer("AVAILABLE", "6"));
        when(repository.findTables("AERIS")).thenReturn(List.of(table, table(6L, "6", 4, true, true)));
        when(repository.findOrder(12L)).thenReturn(Optional.of(current));
        when(repository.findTableByCode("AERIS", "5")).thenReturn(Optional.of(table));
        when(repository.changeReservation(eq(12L), any(TableReservationChangeCommand.class), eq(table))).thenReturn(changed);

        TableReservationOrder result = service.changeByGuest(12L, command);

        var sync = forClass(ExternalReservationResult.class);
        assertThat(result).isEqualTo(changed);
        verify(notificationService).notifyHostessApprovalRequest(eq(changed), sync.capture());
        assertThat(sync.getValue().status()).isEqualTo(TableReservationService.EXTERNAL_CHANGE_NOT_SYNCED);
        assertThat(sync.getValue().providerConfigured()).isTrue();
        assertThat(sync.getValue().created()).isFalse();
        verify(externalProvider, never()).reserve(any(), any());
        verify(repository, never()).attachExternalId(any(), any());
    }

    @Test
    void rejectsReservationAndDoesNotNotifyHostess() {
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationOrder awaiting = order(12L, table, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
        TableReservationOrder rejected = order(12L, table, TableReservationStatus.REJECTED);

        when(repository.findOrder(12L)).thenReturn(Optional.of(awaiting));
        when(repository.reject(12L)).thenReturn(rejected);

        TableReservationOrder result = service.reject(12L);

        assertThat(result.status()).isEqualTo(TableReservationStatus.REJECTED);
        verify(externalProvider, never()).cancelReservation(any());
    }

    @Test
    void rejectionAndGuestCancellationRemoveTheExternalBooking() {
        VenueTable table = table(5L, "5", 4, true, true);
        TableReservationOrder awaiting = withExternalId(order(12L, table), "saby-1");
        TableReservationOrder rejected = withExternalId(order(12L, table, TableReservationStatus.REJECTED), "saby-1");
        TableReservationOrder confirmed = withExternalId(order(13L, table, TableReservationStatus.CONFIRMED), "saby-2");
        TableReservationOrder cancelled = withExternalId(order(13L, table, TableReservationStatus.CANCELLED), "saby-2");

        when(repository.findOrder(12L)).thenReturn(Optional.of(awaiting));
        when(repository.reject(12L)).thenReturn(rejected);
        when(repository.findOrder(13L)).thenReturn(Optional.of(confirmed));
        when(repository.cancel(13L)).thenReturn(cancelled);
        when(externalProvider.cancelReservation("saby-1")).thenReturn(true);
        when(externalProvider.cancelReservation("saby-2")).thenReturn(false);

        service.reject(12L);
        service.cancelByGuest(13L);

        verify(externalProvider).cancelReservation("saby-1");
        verify(externalProvider).cancelReservation("saby-2");
        verify(notificationService, never()).notifyHostessExternalCancelFailed(rejected);
        verify(notificationService).notifyHostessExternalCancelFailed(cancelled);
    }

    @Test
    void validatesTimeWindow() {
        Instant start = Instant.parse("2026-06-06T19:00:00Z");
        Instant end = Instant.parse("2026-06-06T17:00:00Z");

        assertThatThrownBy(() -> service.createReservation(command("5", start, end, 3)))
                .isInstanceOf(ApiException.class)
                .hasMessage("requestedEndAt must be after requestedStartAt");
    }

    @Test
    void findsTheGuestsRequestThatCrossesAWindow() {
        // The guest holds a table from 17:00 to 19:00.
        when(repository.findActiveOrdersByChatId(1773317437L, "AERIS")).thenReturn(List.of(held(10L, "17:00", "19:00", TableReservationStatus.CONFIRMED)));

        // The same hours, a later start, an earlier start, a window inside and a window around.
        for (String[] window : new String[][]{{"17:00", "19:00"}, {"17:15", "18:45"}, {"16:45", "18:15"}, {"18:45", "20:15"}, {"16:00", "20:00"}}) {
            assertThat(service.findOverlappingReservation(1773317437L, "AERIS", at(window[0]), at(window[1])))
                    .as(window[0] + " to " + window[1])
                    .map(TableReservationOrder::id)
                    .contains(10L);
        }
    }

    @Test
    void aWindowThatOnlyTouchesARequestDoesNotCrossIt() {
        when(repository.findActiveOrdersByChatId(1773317437L, "AERIS")).thenReturn(List.of(held(10L, "17:00", "19:00", TableReservationStatus.CONFIRMED)));

        // Until 17:00 sharp, from 19:00 sharp, and well apart on either side.
        for (String[] window : new String[][]{{"15:00", "17:00"}, {"19:00", "21:00"}, {"12:00", "13:30"}, {"21:00", "23:00"}}) {
            assertThat(service.findOverlappingReservation(1773317437L, "AERIS", at(window[0]), at(window[1]))).as(window[0] + " to " + window[1]).isEmpty();
        }
        assertThat(service.findOverlappingReservation(1773317437L, "AERIS", at("17:00"), null)).isEmpty();
        assertThat(service.findOverlappingReservation(1773317437L, "AERIS", null, at("19:00"))).isEmpty();
    }

    @Test
    void aRequestThatNoLongerHoldsATableIsNotInTheWay() {
        when(repository.findActiveOrdersByChatId(1773317437L, "AERIS")).thenReturn(List.of(
                held(10L, "17:00", "19:00", TableReservationStatus.CANCELLED),
                held(11L, "17:00", "19:00", TableReservationStatus.REJECTED),
                held(12L, "17:00", "19:00", TableReservationStatus.EXPIRED),
                held(13L, "17:00", "19:00", TableReservationStatus.DRAFT)));

        assertThat(service.findOverlappingReservation(1773317437L, "AERIS", at("17:00"), at("19:00"))).isEmpty();
    }

    @Test
    void looksOnlyAtTheVenueAskedAboutAndNamesTheEarliestRequest() {
        when(repository.findActiveOrdersByChatId(1773317437L, "AERIS")).thenReturn(List.of(
                held(21L, "18:30", "20:30", TableReservationStatus.AWAITING_MANAGER_CONFIRMATION),
                held(20L, "16:30", "18:00", TableReservationStatus.CONFIRMED)));

        assertThat(service.findOverlappingReservation(1773317437L, "AERIS", at("17:00"), at("19:00"))).map(TableReservationOrder::id).contains(20L);
        // The guest holds nothing at the other venue, so the same hours are free there.
        assertThat(service.findOverlappingReservation(1773317437L, "OTHER", at("17:00"), at("19:00"))).isEmpty();
        verify(repository).findActiveOrdersByChatId(1773317437L, "OTHER");
        assertThatThrownBy(() -> service.findOverlappingReservation(null, "AERIS", at("17:00"), at("19:00"))).isInstanceOf(ApiException.class);
    }

    private Instant at(String time) {
        return Instant.parse("2026-06-06T" + time + ":00Z");
    }

    private TableReservationOrder held(Long id, String from, String to, TableReservationStatus status) {
        TableReservationOrder order = order(id, table(4L, "4", 4, true, true), status);
        return new TableReservationOrder(order.id(), order.chatId(), order.telegramUserId(), order.userId(), order.tableId(), order.tableCode(),
                order.tableDisplayName(), order.preferredZone(), order.seatingPreference(), order.status(), order.source(), at(from), at(to),
                order.partySize(), order.guestName(), order.guestPhone(), order.guestComment(), order.managerTelegramId(), order.managerUserId(),
                order.hostessChatId(), order.sbisExternalId(), order.createdAt(), order.updatedAt());
    }

    private TableReservationCommand command(String tableCode, Instant start, Instant end, int partySize) {
        return new TableReservationCommand(
                1773317437L,
                1773317437L,
                null,
                "AERIS",
                tableCode,
                null,
                "Хочу спокойный стол",
                start,
                end,
                partySize,
                "Наталья",
                "+79990000000",
                "Хочу спокойный стол",
                876857557L,
                null
        );
    }

    private VenueTable table(Long id, String code, int capacity, boolean bookable, boolean active) {
        return new VenueTable(
                id,
                "AERIS",
                code,
                "Table " + code,
                "MAIN_HALL",
                1,
                capacity,
                null,
                bookable,
                active,
                2,
                "AERIS PLAN",
                Integer.parseInt(code),
                Instant.parse("2026-06-05T00:00:00Z"),
                Instant.parse("2026-06-05T00:00:00Z")
        );
    }

    private ExternalAvailabilityResult externalAnswer(String status, String... freeTableNames) {
        List<Map<String, Object>> candidates = Arrays.stream(freeTableNames)
                .map(name -> Map.<String, Object>of("tableName", name, "capacity", 4))
                .toList();
        return new ExternalAvailabilityResult(
                !candidates.isEmpty(), true, "SABY", status, "", List.of(), Map.of("candidates", candidates, "hasMore", false));
    }

    private ExternalReservationResult externalResult(boolean created, String status, String externalId) {
        return new ExternalReservationResult(created, true, "SABY", status, externalId, "", List.of(), Map.of());
    }

    private TableReservationOrder withExternalId(TableReservationOrder order, String externalId) {
        return new TableReservationOrder(
                order.id(),
                order.chatId(),
                order.telegramUserId(),
                order.userId(),
                order.tableId(),
                order.tableCode(),
                order.tableDisplayName(),
                order.preferredZone(),
                order.seatingPreference(),
                order.status(),
                order.source(),
                order.requestedStartAt(),
                order.requestedEndAt(),
                order.partySize(),
                order.guestName(),
                order.guestPhone(),
                order.guestComment(),
                order.managerTelegramId(),
                order.managerUserId(),
                order.hostessChatId(),
                externalId,
                order.createdAt(),
                order.updatedAt()
        );
    }

    private TableReservationOrder order(Long id, VenueTable table) {
        return order(id, table, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
    }

    private TableReservationOrder order(Long id, VenueTable table, TableReservationStatus status) {
        return new TableReservationOrder(
                id,
                1773317437L,
                1773317437L,
                null,
                table.id(),
                table.tableCode(),
                table.displayName(),
                null,
                "Хочу спокойный стол",
                status,
                "TELEGRAM",
                Instant.parse("2026-06-06T17:00:00Z"),
                Instant.parse("2026-06-06T19:00:00Z"),
                3,
                "Наталья",
                "+79990000000",
                "Хочу спокойный стол",
                876857557L,
                null,
                null,
                null,
                Instant.parse("2026-06-05T00:00:00Z"),
                Instant.parse("2026-06-05T00:00:00Z")
        );
    }
}
