package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableAvailability;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.booking.VenueTable;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TableAvailabilityToolTest {

    // Wednesday 2026-10-14, 10:00 in Yekaterinburg (UTC+5).
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneOffset.UTC);

    private TableReservationService reservations;
    private TableAvailabilityTool tool;

    @BeforeEach
    void setUp() {
        reservations = mock(TableReservationService.class);
        tool = new TableAvailabilityTool(reservations, new VenueOpeningHours(), new CapabilityProperties(),
                new GuestJourneyProperties(), CLOCK);
    }

    @Test
    void aSlotThatHasBegunIsAlreadyPassed() {
        // Prod run 16.10: «вчера в 19:00» was put to the guest as a booking to confirm.
        CapabilityResult result = tool.invoke(null, Map.of("date", "2026-10-13", "time", "19:00", "partySize", 2));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).isEqualTo(Map.of("reason", "already_passed", "date", "2026-10-13", "dateLabel", "вт 13.10",
                "time", "19:00"));
        verify(reservations, never()).availability(anyString(), any(), any(), anyInt());
    }

    @Test
    void zeroGuestsIsABadArgument() {
        CapabilityResult result = tool.invoke(null, Map.of("date", "2026-10-15", "time", "19:00", "partySize", 0));

        assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        verify(reservations, never()).availability(anyString(), any(), any(), anyInt());
    }

    @Test
    void unreadableTimeIsABadArgument() {
        assertThat(tool.invoke(null, Map.of("date", "2026-10-15", "time", "вечером", "partySize", "2")).error())
                .isEqualTo(CapabilityResult.BAD_ARGUMENT);
    }

    @Test
    void freeTablesNeedNoAlternatives() {
        // 19:00 local on 2026-10-15 is 14:00Z; the slot lasts 90 minutes.
        when(reservations.availability(eq("AERIS"), eq(Instant.parse("2026-10-15T14:00:00Z")),
                eq(Instant.parse("2026-10-15T15:30:00Z")), eq(4)))
                .thenReturn(List.of(TableAvailability.available(table("T1", "Стол у окна", 4))));

        CapabilityResult result = tool.invoke(null, Map.of("date", "2026-10-15", "time", "19:00", "partySize", 4));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts().get("free")).isEqualTo(List.of(
                Map.of("tableCode", "T1", "name", "Стол у окна", "capacity", 4)));
        assertThat(result.facts().get("alternatives")).isEqualTo(List.of());
        verify(reservations, times(1)).availability(anyString(), any(), any(), anyInt());
    }

    @Test
    void noFreeTableLooksForAlternativesWithinSixProbes() {
        when(reservations.availability(anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(reservations.availability(eq("AERIS"), eq(Instant.parse("2026-10-15T14:30:00Z")), any(), eq(4)))
                .thenReturn(List.of(TableAvailability.available(table("T5", "Пятый", 6))));
        when(reservations.availability(eq("AERIS"), eq(Instant.parse("2026-10-15T14:45:00Z")), any(), eq(4)))
                .thenReturn(List.of(TableAvailability.available(table("T5", "Пятый", 6))));
        when(reservations.availability(eq("AERIS"), eq(Instant.parse("2026-10-15T15:30:00Z")), any(), eq(4)))
                .thenReturn(List.of(TableAvailability.available(table("T7", "Седьмой", 4))));

        CapabilityResult result = tool.invoke(null, Map.of("date", "2026-10-15", "time", "19:00", "partySize", 4));

        assertThat(result.facts().get("free")).isEqualTo(List.of());
        assertThat(result.facts().get("alternatives")).isEqualTo(List.of(
                Map.of("time", "19:30", "tableCode", "T5"),
                Map.of("time", "19:45", "tableCode", "T5"),
                Map.of("time", "20:30", "tableCode", "T7")));
    }

    @Test
    void probesStopAfterSix() {
        when(reservations.availability(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        CapabilityResult result = tool.invoke(null, Map.of("date", "2026-10-15", "time", "19:00", "partySize", 4));

        assertThat(result.facts().get("alternatives")).isEqualTo(List.of());
        // The asked slot plus at most six later probes (19:15 .. 20:30).
        verify(reservations, times(7)).availability(anyString(), any(), any(), anyInt());
    }

    @Test
    void closedHoursHaveNoFreeTables() {
        CapabilityResult result = tool.invoke(null, Map.of("date", "2026-10-15", "time", "09:00", "partySize", 2));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("open", false).containsEntry("free", List.of());
        verify(reservations, never()).availability(anyString(), eq(Instant.parse("2026-10-15T04:00:00Z")), any(), anyInt());
    }

    private static VenueTable table(String code, String name, int capacity) {
        return new VenueTable(1L, "AERIS", code, name, "MAIN", 1, capacity, null, true, true, null, null, 1, null, null);
    }
}
