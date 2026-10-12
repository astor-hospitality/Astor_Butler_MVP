package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.billing.BillingSync;
import museon_online.astor_butler.domain.booking.external.ExternalBookingSnapshot;
import museon_online.astor_butler.domain.booking.external.ExternalBookingState;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The restaurant's own system is the one that finally accepts or drops a booking Butler wrote there.
 * {@link ExternalBookingSync} reads it back and moves the local order the same way, once, without writing anything
 * back to that system.
 */
class ExternalBookingSyncTest {

    private final TableReservationRepository repository = mock(TableReservationRepository.class);
    private final TableReservationNotificationService notificationService = mock(TableReservationNotificationService.class);
    private final ExternalReservationProvider externalProvider = mock(ExternalReservationProvider.class);
    private final TableReservationService service = new TableReservationService(repository, notificationService, externalProvider);
    private final BillingSync billingSync = mock(BillingSync.class);
    private final ExternalBookingSync sync = new ExternalBookingSync(repository, service, externalProvider, billingSync);

    @BeforeEach
    void providerIsOn() {
        when(externalProvider.providerId()).thenReturn("SABY");
        when(externalProvider.status()).thenReturn(new ExternalReservationStatus("SABY", true, true, List.of(), "READ_WRITE"));
    }

    @Test
    void confirmedInTheVenueSystemConfirmsTheLocalOrderOnce() {
        TableReservationOrder awaiting = order(10L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "ext-10");
        TableReservationOrder confirmed = order(10L, TableReservationStatus.CONFIRMED, "ext-10");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(awaiting));
        when(repository.findOrder(10L)).thenReturn(Optional.of(awaiting));
        when(repository.confirm(10L)).thenReturn(confirmed);
        when(externalProvider.fetchReservationState("ext-10")).thenReturn(snapshot("ext-10", ExternalBookingState.CONFIRMED, "SABY_STATE_20"));

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(report.cancelled()).isZero();
        verify(repository).confirm(10L);
        verify(notificationService).notifyGuestConfirmed(confirmed);
        verify(notificationService).notifyHostessConfirmed(confirmed);
        verify(externalProvider, never()).cancelReservation(any());
    }

    @Test
    void cancelledInTheVenueSystemRejectsAnAwaitingOrderWithoutCancellingItThereAgain() {
        TableReservationOrder awaiting = order(11L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "ext-11");
        TableReservationOrder rejected = order(11L, TableReservationStatus.REJECTED, "ext-11");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(awaiting));
        when(repository.findOrder(11L)).thenReturn(Optional.of(awaiting));
        when(repository.reject(11L)).thenReturn(rejected);
        when(repository.findAlternativeTables(any(), any(), any(), anyInt(), any(), any())).thenReturn(List.of());
        when(externalProvider.fetchReservationState("ext-11")).thenReturn(snapshot("ext-11", ExternalBookingState.CANCELLED, "SABY_STATE_220"));

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.cancelled()).isEqualTo(1);
        verify(repository).reject(11L);
        verify(notificationService).notifyGuestRejected(eq(rejected), any());
        verify(externalProvider, never()).cancelReservation(any());
    }

    @Test
    void cancelledInTheVenueSystemCancelsAConfirmedOrderAndTellsTheGuest() {
        TableReservationOrder confirmed = order(12L, TableReservationStatus.CONFIRMED, "ext-12");
        TableReservationOrder cancelled = order(12L, TableReservationStatus.CANCELLED, "ext-12");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(confirmed));
        when(repository.findOrder(12L)).thenReturn(Optional.of(confirmed));
        when(repository.cancel(12L)).thenReturn(cancelled);
        when(repository.findAlternativeTables(any(), any(), any(), anyInt(), any(), any())).thenReturn(List.of());
        when(externalProvider.fetchReservationState("ext-12")).thenReturn(snapshot("ext-12", ExternalBookingState.CANCELLED, "SABY_STATE_220"));

        sync.run();

        verify(repository).cancel(12L);
        verify(notificationService).notifyGuestRejected(eq(cancelled), any());
        verify(externalProvider, never()).cancelReservation(any());
    }

    @Test
    void pendingUnknownAndCompletedChangeNothing() {
        TableReservationOrder a = order(13L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "ext-13");
        TableReservationOrder b = order(14L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "ext-14");
        TableReservationOrder c = order(15L, TableReservationStatus.CONFIRMED, "ext-15");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(a, b, c));
        when(externalProvider.fetchReservationState("ext-13")).thenReturn(snapshot("ext-13", ExternalBookingState.PENDING, "SABY_STATE_10"));
        when(externalProvider.fetchReservationState("ext-14")).thenReturn(ExternalBookingSnapshot.unknown("SABY", "ext-14", "PROVIDER_TIMEOUT"));
        when(externalProvider.fetchReservationState("ext-15")).thenReturn(snapshot("ext-15", ExternalBookingState.COMPLETED, "SABY_STATE_200"));

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.checked()).isEqualTo(3);
        assertThat(report.unknown()).isEqualTo(1);
        verify(repository, never()).confirm(any());
        verify(repository, never()).reject(any());
        verify(repository, never()).cancel(any());
        verifyNoMoreInteractions(notificationService);
        // Billing hears every read, including the one that said nothing, and the completed visit above all.
        verify(billingSync).onVenueSnapshot(eq("SABY"), eq("ext-13"), eq(ExternalBookingState.PENDING), any());
        verify(billingSync).onVenueSnapshot(eq("SABY"), eq("ext-14"), eq(ExternalBookingState.UNKNOWN), any());
        verify(billingSync).onVenueSnapshot(eq("SABY"), eq("ext-15"), eq(ExternalBookingState.COMPLETED), any());
    }

    @Test
    void seatedInTheVenueSystemSeatsAConfirmedOrderOnceAndTheHostessHears() {
        TableReservationOrder confirmed = order(20L, TableReservationStatus.CONFIRMED, "ext-20");
        TableReservationOrder seated = seated(20L, "ext-20");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(confirmed));
        when(repository.findOrder(20L)).thenReturn(Optional.of(confirmed), Optional.of(confirmed), Optional.of(seated));
        when(repository.seatIfConfirmed(20L)).thenReturn(true);
        when(externalProvider.fetchReservationState("ext-20")).thenReturn(snapshot("ext-20", ExternalBookingState.SEATED, "SABY_STATE_50"));

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.seated()).isEqualTo(1);
        assertThat(report.confirmed()).isZero();
        verify(repository).seatIfConfirmed(20L);
        verify(repository, never()).confirm(any());
        verify(notificationService).notifyHostessSeatedByVenue(seated);
        verify(notificationService).traceSeated(seated, "Presto");
        verify(notificationService, never()).notifyGuestConfirmed(any());
        verify(billingSync).onVenueSnapshot(eq("SABY"), eq("ext-20"), eq(ExternalBookingState.SEATED), any());
    }

    @Test
    void seatedInTheVenueSystemConfirmsAnAwaitingOrderFirstOneStepAtATime() {
        TableReservationOrder awaiting = order(21L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "ext-21");
        TableReservationOrder confirmed = order(21L, TableReservationStatus.CONFIRMED, "ext-21");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(awaiting));
        when(repository.findOrder(21L)).thenReturn(Optional.of(awaiting));
        when(repository.confirm(21L)).thenReturn(confirmed);
        when(externalProvider.fetchReservationState("ext-21")).thenReturn(snapshot("ext-21", ExternalBookingState.SEATED, "SABY_STATE_50"));

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(report.seated()).isZero();
        verify(repository).confirm(21L);
        verify(repository, never()).seatIfConfirmed(any());
        verify(notificationService).notifyGuestConfirmed(confirmed);
    }

    @Test
    void anOrderAlreadySeatedIsLeftAloneAndStillFollowsACancellation() {
        TableReservationOrder seated = seated(22L, "ext-22");
        TableReservationOrder seatedToo = seated(23L, "ext-23");
        TableReservationOrder cancelled = order(23L, TableReservationStatus.CANCELLED, "ext-23");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(seated, seatedToo));
        when(externalProvider.fetchReservationState("ext-22")).thenReturn(snapshot("ext-22", ExternalBookingState.SEATED, "SABY_STATE_50"));
        when(externalProvider.fetchReservationState("ext-23")).thenReturn(snapshot("ext-23", ExternalBookingState.CANCELLED, "SABY_STATE_220"));
        when(repository.findOrder(23L)).thenReturn(Optional.of(seatedToo));
        when(repository.cancel(23L)).thenReturn(cancelled);
        when(repository.findAlternativeTables(any(), any(), any(), anyInt(), any(), any())).thenReturn(List.of());

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.seated()).isZero();
        assertThat(report.cancelled()).isEqualTo(1);
        verify(repository, never()).seatIfConfirmed(any());
        verify(repository).cancel(23L);
        verify(notificationService).notifyGuestRejected(eq(cancelled), any());

        // Presto still says «confirmed» about a guest Butler already seated: nothing moves back.
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(seated));
        when(externalProvider.fetchReservationState("ext-22")).thenReturn(snapshot("ext-22", ExternalBookingState.CONFIRMED, "SABY_STATE_20"));
        assertThat(sync.run().confirmed()).isZero();
        verify(repository, never()).confirm(any());
    }

    @Test
    void alreadyConfirmedOrderIsNotConfirmedTwice() {
        TableReservationOrder confirmed = order(16L, TableReservationStatus.CONFIRMED, "ext-16");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(confirmed));
        when(externalProvider.fetchReservationState("ext-16")).thenReturn(snapshot("ext-16", ExternalBookingState.CONFIRMED, "SABY_STATE_20"));

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.confirmed()).isZero();
        verify(repository, never()).confirm(any());
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void oneFailingOrderDoesNotStopTheOthers() {
        TableReservationOrder broken = order(17L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "ext-17");
        TableReservationOrder fine = order(18L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "ext-18");
        TableReservationOrder confirmed = order(18L, TableReservationStatus.CONFIRMED, "ext-18");
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of(broken, fine));
        when(externalProvider.fetchReservationState("ext-17")).thenThrow(new IllegalStateException("boom"));
        when(externalProvider.fetchReservationState("ext-18")).thenReturn(snapshot("ext-18", ExternalBookingState.CONFIRMED, "SABY_STATE_20"));
        when(repository.findOrder(18L)).thenReturn(Optional.of(fine));
        when(repository.confirm(18L)).thenReturn(confirmed);

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.confirmed()).isEqualTo(1);
    }

    @Test
    void doesNothingWhileTheProviderIsOff() {
        when(externalProvider.status()).thenReturn(ExternalReservationStatus.disabled("SABY", List.of()));

        ExternalBookingSync.Report report = sync.run();

        assertThat(report.checked()).isZero();
        verify(repository, never()).findActiveOrdersWithExternalId(anyInt());
    }

    @Test
    void aFullUnchangedBatchDoesNotStarveLaterBookingsAndTheSweepRestarts() {
        var first = java.util.stream.LongStream.rangeClosed(1, ExternalBookingSync.BATCH)
                .mapToObj(id -> order(id, TableReservationStatus.CONFIRMED, "ext-" + id)).toList();
        when(repository.findActiveOrdersWithExternalId(ExternalBookingSync.BATCH)).thenReturn(first);
        when(repository.findActiveOrdersWithExternalIdAfter(200L, ExternalBookingSync.BATCH))
                .thenReturn(List.of(order(201L, TableReservationStatus.CONFIRMED, "ext-201")));
        when(externalProvider.fetchReservationState(any())).thenReturn(
                snapshot("any", ExternalBookingState.COMPLETED, "SABY_STATE_200"));
        assertThat(sync.run().checked()).isEqualTo(200);
        assertThat(sync.run().checked()).isEqualTo(1);
        verify(externalProvider).fetchReservationState("ext-201");
        assertThat(sync.run().checked()).isEqualTo(200);
        verify(repository, org.mockito.Mockito.times(2)).findActiveOrdersWithExternalId(200);
    }

    private static ExternalBookingSnapshot snapshot(String externalId, ExternalBookingState state, String status) {
        return new ExternalBookingSnapshot("SABY", externalId, state, status, Map.of());
    }

    private static TableReservationOrder seated(Long id, String externalId) {
        Instant start = Instant.parse("2026-10-07T14:00:00Z");
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4", "WINDOW", null,
                TableReservationStatus.SEATED, "TELEGRAM", start, start.plusSeconds(7200), 2, "Иван", "+79990000000", null,
                876857557L, null, null, externalId, start, start, start.plusSeconds(300));
    }

    private static TableReservationOrder order(Long id, TableReservationStatus status, String externalId) {
        Instant start = Instant.parse("2026-10-07T14:00:00Z");
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4", "WINDOW", null, status,
                "TELEGRAM", start, start.plusSeconds(7200), 2, "Иван", "+79990000000", null, 876857557L, null, null,
                externalId, start, start);
    }
}
