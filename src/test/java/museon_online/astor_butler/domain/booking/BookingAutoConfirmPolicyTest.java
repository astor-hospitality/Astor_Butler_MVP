package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static museon_online.astor_butler.domain.booking.BookingAutoConfirmPolicy.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which unanswered hostess cards confirm themselves and which wait for a person. */
class BookingAutoConfirmPolicyTest {

    private final BookingAutoConfirmProperties properties = new BookingAutoConfirmProperties();
    private final BookingHostessStateStorage storage = mock(BookingHostessStateStorage.class);
    private final TableReservationRepository repository = mock(TableReservationRepository.class);
    private final BookingAutoConfirmPolicy policy = new BookingAutoConfirmPolicy(properties, storage, repository);

    @BeforeEach
    void featureOnInAutoMode() {
        properties.setEnabled(true);
        when(storage.manualMode()).thenReturn(HostessManualMode.auto());
        when(repository.findTableByCode(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void offWhileTheFeatureIsDisabledAndNothingElseIsAsked() {
        properties.setEnabled(false);

        assertThat(policy.decide(order(2, null, "4", null))).isEqualTo(Decision.OFF);
        verify(storage, never()).manualMode();
        verify(repository, never()).findTableByCode(any(), any());
    }

    @Test
    void anOrdinaryRequestConfirmsItself() {
        assertThat(policy.decide(order(2, "WINDOW", "4", null))).isEqualTo(Decision.AUTO);
    }

    @Test
    void aPartyOfMaxPartyOrMoreNeedsADecision() {
        properties.setMaxParty(6);

        assertThat(policy.decide(order(6, null, "4", null))).isEqualTo(Decision.LARGE_PARTY);
        assertThat(policy.decide(order(9, null, "4", null))).isEqualTo(Decision.LARGE_PARTY);
        assertThat(policy.decide(order(5, null, "4", null))).isEqualTo(Decision.AUTO);
    }

    @Test
    void aWishForAnExcludedZoneNeedsADecision() {
        assertThat(policy.decide(order(2, "VIP_ZONE", "13", null))).isEqualTo(Decision.EXCLUDED_ZONE);
        assertThat(policy.decide(order(2, "vip_zone", "13", null))).isEqualTo(Decision.EXCLUDED_ZONE);
    }

    @Test
    void aTableInAnExcludedZoneNeedsADecisionEvenWithoutAWish() {
        when(repository.findTableByCode("AERIS", "13")).thenReturn(Optional.of(table("13", "VIP_ZONE")));

        assertThat(policy.decide(order(2, null, "13", null))).isEqualTo(Decision.EXCLUDED_ZONE);
    }

    @Test
    void noZoneIsExcludedWhenTheListIsEmpty() {
        properties.setExcludeZones(List.of());
        when(repository.findTableByCode("AERIS", "13")).thenReturn(Optional.of(table("13", "VIP_ZONE")));

        assertThat(policy.decide(order(2, "VIP_ZONE", "13", null))).isEqualTo(Decision.AUTO);
    }

    @Test
    void manualModePausesAutoConfirmForEveryRequest() {
        when(storage.manualMode()).thenReturn(new HostessManualMode(true, "перегружены", "@dasha", Instant.now()));

        assertThat(policy.decide(order(2, null, "4", null))).isEqualTo(Decision.MANUAL_MODE);
        assertThat(policy.decide(order(8, null, "4", null))).isEqualTo(Decision.MANUAL_MODE);
    }

    @Test
    void anOrderPrestoConfirmsIsLeftToPresto() {
        policy.setConfirmationSource(BookingConfirmationSource.VENUE_SYSTEM);

        assertThat(policy.decide(order(2, null, "4", "saby-1"))).isEqualTo(Decision.VENUE_SYSTEM);
        assertThat(policy.decide(order(2, null, "4", null))).isEqualTo(Decision.AUTO);
    }

    @Test
    void timeoutComesFromTheProperties() {
        properties.setTimeoutMinutes(5);

        assertThat(policy.timeoutMinutes()).isEqualTo(5);
        assertThat(policy.enabled()).isTrue();
    }

    private static TableReservationOrder order(int partySize, String preferredZone, String tableCode, String externalId) {
        Instant start = Instant.parse("2026-10-12T14:00:00Z");
        return new TableReservationOrder(7L, 1773317437L, 1773317437L, null, 4L, tableCode, "Стол " + tableCode,
                preferredZone, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM", start,
                start.plusSeconds(7200), partySize, "Иван", "+79990000000", null, 876857557L, null, "500",
                externalId, start, start);
    }

    private static VenueTable table(String code, String zone) {
        return new VenueTable(13L, "AERIS", code, "Стол VIP " + code, zone, 1, 6, null, true, true, 2, null, 7, null, null);
    }
}
