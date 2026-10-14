package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.booking.external.ExternalAvailabilityResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import museon_online.astor_butler.domain.calllog.CallLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The reservation target actions in the unified call log, on the real booking SQL. */
class TableReservationCallLogTest {

    private static final Instant NOW = Instant.now();
    private static final Instant START = NOW.plus(Duration.ofHours(2));
    private static final Instant END = START.plus(Duration.ofHours(2));
    private static final long TABLE_4 = 1L;
    private static final long GUEST = 1001L;

    private final JdbcTemplate jdbc = TableReservationTestDatabase.create();
    private final ExternalReservationProvider externalProvider = mock(ExternalReservationProvider.class);
    private final TableReservationService reservations = new TableReservationService(
            new TableReservationRepository(jdbc), mock(TableReservationNotificationService.class), externalProvider);
    private final CallLogWriter callLog = mock(CallLogWriter.class);

    @BeforeEach
    void venue() {
        TableReservationTestDatabase.table(jdbc, TABLE_4, "4", "MAIN_HALL", 5);
        when(externalProvider.providerId()).thenReturn("SABY");
        when(externalProvider.checkAvailability(any()))
                .thenReturn(ExternalAvailabilityResult.unavailableBecauseUnconfigured("SABY", List.of()));
        when(externalProvider.reserve(any(), any()))
                .thenReturn(ExternalReservationResult.rejectedBecauseUnconfigured("SABY", List.of()));
        reservations.setCallLog(callLog);
    }

    @Test
    void createIsATargetAction() {
        TableReservationRepository repository = mock(TableReservationRepository.class);
        TableReservationService service = new TableReservationService(
                repository, mock(TableReservationNotificationService.class), externalProvider);
        service.setCallLog(callLog);
        when(callLog.enabled()).thenReturn(true);
        VenueTable table = new VenueTable(TABLE_4, "AERIS", "4", "Table 4", "MAIN_HALL", 1, 5, null, true, true, 2, "AERIS PLAN", 4, NOW, NOW);
        TableReservationCommand command = new TableReservationCommand(
                GUEST, GUEST, null, "AERIS", "4", null, null, START, END, 2, "Гость", "+79990000000", null, 876857557L, null);
        when(repository.findTableByCode("AERIS", "4")).thenReturn(java.util.Optional.of(table));
        when(repository.createAwaitingManagerOrder(command, table)).thenReturn(new TableReservationOrder(
                10L, GUEST, GUEST, null, TABLE_4, "4", "Table 4", null, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                "TELEGRAM", START, END, 2, "Гость", "+79990000000", null, 876857557L, null, null, null, NOW, NOW));

        service.createReservation(command);

        verify(callLog).targetAction("reservation:create", "AWAITING_MANAGER_CONFIRMATION", "TELEGRAM", "1001");
    }

    @Test
    void confirmAndSeatAreTargetActions() {
        when(callLog.enabled()).thenReturn(true);
        TableReservationTestDatabase.order(jdbc, 51L, GUEST, TABLE_4, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                START, END, null, NOW.minus(Duration.ofMinutes(5)));
        TableReservationTestDatabase.hold(jdbc, 51L, TABLE_4, "HELD", START, END);

        reservations.confirm(51L);
        reservations.seatByStaff(51L, "@dasha");

        verify(callLog).targetAction(eq("reservation:confirm"), eq("CONFIRMED"), any(), eq("1001"));
        verify(callLog).targetAction(eq("reservation:seated"), eq("SEATED"), any(), eq("1001"));
    }

    @Test
    void venueSeatingIsATargetActionToo() {
        when(callLog.enabled()).thenReturn(true);
        TableReservationTestDatabase.order(jdbc, 52L, GUEST, TABLE_4, TableReservationStatus.CONFIRMED,
                NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(110)), null, NOW.minus(Duration.ofHours(2)));
        TableReservationTestDatabase.hold(jdbc, 52L, TABLE_4, "CONFIRMED", NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(110)));

        reservations.seatFromVenue(52L);

        verify(callLog).targetAction(eq("reservation:seated"), eq("SEATED"), any(), eq("1001"));
    }

    @Test
    void offTheWriterIsNotCalled() {
        TableReservationTestDatabase.order(jdbc, 53L, GUEST, TABLE_4, TableReservationStatus.CONFIRMED,
                NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(110)), null, NOW.minus(Duration.ofHours(2)));
        TableReservationTestDatabase.hold(jdbc, 53L, TABLE_4, "CONFIRMED", NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(110)));

        reservations.seatByStaff(53L, "@dasha");

        verify(callLog, never()).targetAction(any(), any(), any(), any());
    }
}
