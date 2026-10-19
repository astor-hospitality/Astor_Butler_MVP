package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Option A: the Concierge owns a booking its form made for the same guest (Telegram user id) of this turn. */
class ReservationAccessTest {

    private static final long GUEST = 777001L;
    private static final long OTHER_GUEST = 777002L;

    private TableReservationRepository orders;
    private GuestVisitRepository visits;
    private VisitTurnRepository turns;
    private ReservationAccess access;

    @BeforeEach
    void setUp() {
        orders = mock(TableReservationRepository.class);
        visits = mock(GuestVisitRepository.class);
        turns = mock(VisitTurnRepository.class);
        when(orders.findOrder(anyLong())).thenReturn(Optional.empty());
        when(visits.findByReservation(anyLong())).thenReturn(Optional.empty());
        access = new ReservationAccess(orders, visits, turns);
    }

    @Test
    void theConciergeOwnsItsFormBookingOfTheSameGuest() {
        when(orders.findOrder(70L)).thenReturn(Optional.of(CapabilityFixtures.formOrder(70L, GUEST, TableReservationStatus.CONFIRMED)));

        ReservationAccess.Checked checked = access.check(CapabilityFixtures.concierge("k1", null, GUEST), 70L);

        assertThat(checked.allowed()).isTrue();
        assertThat(checked.order().id()).isEqualTo(70L);
    }

    @Test
    void aConciergeChannelBookingOfTheSameGuestIsOwnedToo() {
        TableReservationOrder form = CapabilityFixtures.formOrder(71L, GUEST, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
        TableReservationOrder concierge = new TableReservationOrder(71L, null, GUEST, null, 5L, "T5", "Стол у окна",
                null, null, form.status(), "CONCIERGE", form.requestedStartAt(), form.requestedEndAt(), 2, "Анна",
                "+79120000060", null, null, null, null, null, null, null);
        when(orders.findOrder(71L)).thenReturn(Optional.of(concierge));

        assertThat(access.check(CapabilityFixtures.concierge("k1", null, GUEST), 71L).allowed()).isTrue();
    }

    @Test
    void anotherGuestsFormBookingIsNotAllowed() {
        when(orders.findOrder(70L)).thenReturn(Optional.of(CapabilityFixtures.formOrder(70L, GUEST, TableReservationStatus.CONFIRMED)));

        ReservationAccess.Checked checked = access.check(CapabilityFixtures.concierge("k1", null, OTHER_GUEST), 70L);

        assertThat(checked.allowed()).isFalse();
        assertThat(checked.error().error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
    }

    @Test
    void withoutTheGuestsIdOnlyTheSessionRuleHolds() {
        when(orders.findOrder(70L)).thenReturn(Optional.of(CapabilityFixtures.formOrder(70L, GUEST, TableReservationStatus.CONFIRMED)));
        when(orders.findOrder(42L)).thenReturn(Optional.of(CapabilityFixtures.order(42L, null, TableReservationStatus.CONFIRMED)));
        when(visits.findByReservation(42L)).thenReturn(Optional.of(CapabilityFixtures.visit(8L, 42L, null, JourneyStage.CONFIRMED)));
        when(turns.existsLink(8L, CapabilityFixtures.SESSION)).thenReturn(true);

        assertThat(access.check(CapabilityFixtures.concierge("k1", null, null), 70L).error().error())
                .isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(access.check(CapabilityFixtures.concierge("k1"), 70L).error().error())
                .isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(access.check(CapabilityFixtures.concierge("k1", null, 0L), 70L).allowed()).isFalse();
        // The session's own booking stays the Concierge's, with or without the guest's id.
        assertThat(access.check(CapabilityFixtures.concierge("k1"), 42L).allowed()).isTrue();
        assertThat(access.check(CapabilityFixtures.concierge("k1", null, OTHER_GUEST), 42L).allowed()).isTrue();
    }

    @Test
    void aBookingWithoutAUserIdOrFromAnotherChannelStaysOut() {
        TableReservationOrder noUser = CapabilityFixtures.order(72L, null, TableReservationStatus.CONFIRMED);
        TableReservationOrder form = CapabilityFixtures.formOrder(73L, GUEST, TableReservationStatus.CONFIRMED);
        TableReservationOrder site = new TableReservationOrder(73L, null, GUEST, null, 5L, "T5", "Стол у окна", null,
                null, form.status(), "WEB", form.requestedStartAt(), form.requestedEndAt(), 2, "Анна", "+79120000060",
                null, null, null, null, null, null, null);
        when(orders.findOrder(72L)).thenReturn(Optional.of(noUser));
        when(orders.findOrder(73L)).thenReturn(Optional.of(site));

        assertThat(access.check(CapabilityFixtures.concierge("k1", null, GUEST), 72L).allowed()).isFalse();
        assertThat(access.check(CapabilityFixtures.concierge("k1", null, GUEST), 73L).allowed()).isFalse();
    }

    @Test
    void theButlersBotKeepsTheChatRuleAndIgnoresTheGuestsId() {
        when(orders.findOrder(70L)).thenReturn(Optional.of(CapabilityFixtures.formOrder(70L, GUEST, TableReservationStatus.CONFIRMED)));
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.CONFIRMED)));
        CapabilityContext butlerNamingTheGuest = new CapabilityContext(CapabilityContext.BUTLER_AGENT,
                CapabilityFixtures.BUTLER_CHAT, null, null, "k1", "Анна", "+79120000060", null, null, GUEST);

        assertThat(access.check(butlerNamingTheGuest, 70L).allowed()).isFalse();
        assertThat(access.check(butlerNamingTheGuest, 41L).allowed()).isTrue();
        assertThat(access.check(CapabilityFixtures.butler("k1"), 41L).allowed()).isTrue();
    }

    @Test
    void checkOrOnlyAndOnlyUpcomingKeepTheSessionsBooking() {
        TableReservationOrder nine = CapabilityFixtures.order(9L, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
        when(orders.findActiveOrdersOfSession(eq(CapabilityFixtures.SESSION), any(), any())).thenReturn(List.of(nine));
        when(orders.findOrder(70L)).thenReturn(Optional.of(CapabilityFixtures.formOrder(70L, GUEST, TableReservationStatus.CONFIRMED)));
        CapabilityContext ctx = CapabilityFixtures.concierge("k1", null, GUEST);

        // The form booking named by id is the guest's own: that one, not the session's only booking.
        assertThat(access.checkOrOnly(ctx, Map.of("reservationId", 70)).order().id()).isEqualTo(70L);
        // An invented id still means the session's only booking; another guest's form booking does not slip in.
        assertThat(access.checkOrOnly(ctx, Map.of("reservationId", 1)).order().id()).isEqualTo(9L);
        assertThat(access.checkOrOnly(CapabilityFixtures.concierge("k1", null, OTHER_GUEST), Map.of("reservationId", 70))
                .order().id()).isEqualTo(9L);
        assertThat(access.onlyUpcoming(ctx, "AERIS", CapabilityFixtures.START.minus(Duration.ofHours(1))))
                .map(TableReservationOrder::id).contains(9L);
    }
}
