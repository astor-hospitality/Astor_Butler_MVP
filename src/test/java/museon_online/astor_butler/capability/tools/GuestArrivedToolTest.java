package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.HostessSeatedService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** «Мы пришли» from the guest: whose booking, the window around its time, and the answer facts. */
class GuestArrivedToolTest {

    /** 18:40 at the venue, twenty minutes before the 19:00 booking of {@link CapabilityFixtures#START}. */
    private static final Instant NOW = CapabilityFixtures.START.minusSeconds(20 * 60);
    private static final Instant SEATED_AT = Instant.parse("2026-10-20T13:41:00Z");

    private HostessSeatedService seating;
    private TableReservationService reservations;
    private TableReservationRepository orders;
    private GuestVisitRepository visits;
    private VisitTurnRepository turns;
    private GuestArrivedTool tool;

    @BeforeEach
    void setUp() {
        seating = mock(HostessSeatedService.class);
        reservations = mock(TableReservationService.class);
        orders = mock(TableReservationRepository.class);
        visits = mock(GuestVisitRepository.class);
        turns = mock(VisitTurnRepository.class);
        when(orders.findOrder(anyLong())).thenReturn(Optional.empty());
        when(visits.findByReservation(anyLong())).thenReturn(Optional.empty());
        tool = new GuestArrivedTool(seating, reservations, new ReservationAccess(orders, visits, turns),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theModelCallsItOnlyOnTheGuestsWordsAndMayLeaveTheIdOut() {
        assertThat(tool.name()).isEqualTo("guest_arrived");
        assertThat(tool.commitment()).isFalse();
        assertThat(tool.description()).contains("«мы пришли»", "outside_window");
        assertThat(tool.parameters()).containsEntry("required", List.of());
    }

    @Test
    void theGuestsOwnBookingIsSeatedAndTheAnswerCarriesTheTableAndTime() {
        sessionHolds42();
        TableReservationOrder seated = seated(42L, null);
        when(seating.guestArrived(42L, NOW)).thenReturn(arrival(HostessSeatedService.ArrivalOutcome.SEATED, seated));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), Map.of("reservationId", 42));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 42L).containsEntry("status", "SEATED")
                .containsEntry("table", "T5").containsEntry("seatedAt", "18:41").containsEntry("alreadySeated", false);
    }

    @Test
    void aMissingOrInventedIdMeansTheSessionsOnlyBooking() {
        TableReservationOrder nine = CapabilityFixtures.order(9L, null, TableReservationStatus.CONFIRMED);
        when(orders.findActiveOrdersOfSession(eq(CapabilityFixtures.SESSION), any(), any())).thenReturn(List.of(nine));
        when(seating.guestArrived(9L, NOW)).thenReturn(arrival(HostessSeatedService.ArrivalOutcome.SEATED, seated(9L, null)));

        CapabilityResult missing = tool.invoke(CapabilityFixtures.concierge("k1"), Map.of());
        CapabilityResult invented = tool.invoke(CapabilityFixtures.concierge("k2"), Map.of("reservationId", 1));

        assertThat(missing.ok()).isTrue();
        assertThat(missing.facts()).containsEntry("reservationId", 9L);
        assertThat(invented.facts()).containsEntry("reservationId", 9L);
    }

    @Test
    void inTheBotAMissingIdMeansTheChatsOnlyBooking() {
        TableReservationOrder own = CapabilityFixtures.order(7L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED);
        when(reservations.listActiveReservationsByChatId(CapabilityFixtures.BUTLER_CHAT)).thenReturn(List.of(own));
        when(orders.findOrder(7L)).thenReturn(Optional.of(own));
        when(seating.guestArrived(7L, NOW)).thenReturn(arrival(HostessSeatedService.ArrivalOutcome.SEATED,
                seated(7L, CapabilityFixtures.BUTLER_CHAT)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 7L);
    }

    @Test
    void withTwoBookingsOrSomeoneElsesBookingNothingIsSeated() {
        when(reservations.listActiveReservationsByChatId(CapabilityFixtures.BUTLER_CHAT)).thenReturn(List.of(
                CapabilityFixtures.order(7L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED),
                CapabilityFixtures.order(8L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED)));
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, 999L, TableReservationStatus.CONFIRMED)));

        CapabilityResult missing = tool.invoke(CapabilityFixtures.butler("k1"), Map.of());
        CapabilityResult otherChat = tool.invoke(CapabilityFixtures.butler("k2"), Map.of("reservationId", 41));

        assertThat(missing.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        assertThat(otherChat.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        verify(seating, never()).guestArrived(anyLong(), any());
    }

    @Test
    void outsideTheWindowTheGuestHearsTheBookingTime() {
        sessionHolds42();
        TableReservationOrder confirmed = CapabilityFixtures.order(42L, null, TableReservationStatus.CONFIRMED);
        when(seating.guestArrived(42L, NOW)).thenReturn(new HostessSeatedService.Arrival(
                HostessSeatedService.ArrivalOutcome.OUTSIDE_WINDOW, confirmed,
                CapabilityFixtures.START.minusSeconds(3600), CapabilityFixtures.START.plusSeconds(5400)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), Map.of("reservationId", 42));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", "outside_window").containsEntry("reservationId", 42L)
                .containsEntry("date", "2026-10-20").containsEntry("time", "19:00")
                .containsEntry("arrivalFrom", "18:00").containsEntry("arrivalTo", "20:30");
    }

    @Test
    void aSecondArrivalKeepsTheFirstTime() {
        sessionHolds42();
        when(seating.guestArrived(42L, NOW)).thenReturn(arrival(HostessSeatedService.ArrivalOutcome.ALREADY_SEATED, seated(42L, null)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), Map.of("reservationId", 42));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("alreadySeated", true).containsEntry("seatedAt", "18:41");
    }

    @Test
    void anUnconfirmedBookingIsNotSeated() {
        sessionHolds42();
        when(seating.guestArrived(42L, NOW)).thenReturn(arrival(HostessSeatedService.ArrivalOutcome.NOT_CONFIRMED,
                CapabilityFixtures.order(42L, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), Map.of("reservationId", 42));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", "not_confirmed").containsEntry("status", "AWAITING_HOSTESS");
    }

    private void sessionHolds42() {
        when(orders.findOrder(42L)).thenReturn(Optional.of(CapabilityFixtures.order(42L, null, TableReservationStatus.CONFIRMED)));
        when(visits.findByReservation(42L)).thenReturn(Optional.of(CapabilityFixtures.visit(8L, 42L, null, JourneyStage.CONFIRMED)));
        when(turns.existsLink(8L, CapabilityFixtures.SESSION)).thenReturn(true);
    }

    private static HostessSeatedService.Arrival arrival(HostessSeatedService.ArrivalOutcome outcome, TableReservationOrder order) {
        return new HostessSeatedService.Arrival(outcome, order, CapabilityFixtures.START.minusSeconds(3600),
                CapabilityFixtures.START.plusSeconds(5400));
    }

    private static TableReservationOrder seated(long id, Long chatId) {
        TableReservationOrder o = CapabilityFixtures.order(id, chatId, TableReservationStatus.SEATED);
        return new TableReservationOrder(o.id(), o.chatId(), o.telegramUserId(), o.userId(), o.tableId(), o.tableCode(),
                o.tableDisplayName(), o.preferredZone(), o.seatingPreference(), o.status(), o.source(), o.requestedStartAt(),
                o.requestedEndAt(), o.partySize(), o.guestName(), o.guestPhone(), o.guestComment(), o.managerTelegramId(),
                o.managerUserId(), o.hostessChatId(), o.sbisExternalId(), o.createdAt(), o.updatedAt(), SEATED_AT);
    }
}
