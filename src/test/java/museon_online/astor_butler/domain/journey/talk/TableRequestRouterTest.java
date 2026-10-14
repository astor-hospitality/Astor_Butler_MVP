package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TableRequestRouterTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:20:00Z");
    private final TableRequestRouter.Reservations reservations = mock(TableRequestRouter.Reservations.class);
    private final TableRequestRouter.Waiters waiters = mock(TableRequestRouter.Waiters.class);
    private final TableRequestRouter.StaffChat staffChat = mock(TableRequestRouter.StaffChat.class);
    private final TableRequestRouter router = new TableRequestRouter(reservations, waiters, staffChat,
            Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Yekaterinburg");

    private static TableReservationOrder order() {
        return new TableReservationOrder(42L, 421L, 421L, null, 5L, "13", "Стол 13", null, null, TableReservationStatus.SEATED,
                "TELEGRAM", NOW, NOW.plusSeconds(5400), 2, "Мария", "+79120000000", null, null, null, null, null, NOW, NOW, NOW);
    }

    @Test
    void breadBecomesACardForTheAssignedWaiter() {
        when(reservations.find(42L)).thenReturn(Optional.of(order()));
        when(waiters.nameOf("13", LocalDate.of(2026, 10, 14))).thenReturn(Optional.of("Кирилл"));

        assertThat(router.route(42L, TableRequest.BREAD, "можно ещё хлеба")).isEqualTo("Передал. Кирилл скоро подойдёт.");
        verify(staffChat).send(eq(order()), eq(TableRequest.BREAD), eq("можно ещё хлеба"), eq("Кирилл"));
    }

    @Test
    void noWaiterAssignedGoesToTheTeam() {
        when(reservations.find(42L)).thenReturn(Optional.of(order()));
        when(waiters.nameOf(any(), any())).thenReturn(Optional.empty());

        assertThat(router.route(42L, TableRequest.WATER, "можно воды")).isEqualTo("Передал команде, скоро подойдут.");
        verify(staffChat).send(eq(order()), eq(TableRequest.WATER), eq("можно воды"), eq(null));
    }

    @Test
    void unknownReservationStillReachesNoOneButAnswersHonestly() {
        when(reservations.find(42L)).thenReturn(Optional.empty());

        assertThat(router.route(42L, TableRequest.WAITER, "позовите официанта")).isEqualTo(TableRequestRouter.NOT_DELIVERED);
    }

    @Test
    void recognisesRequestsButNotWishes() {
        assertThat(TableRequest.recognise("можно ещё хлеба")).contains(TableRequest.BREAD);
        assertThat(TableRequest.recognise("принесите воды, пожалуйста")).contains(TableRequest.WATER);
        assertThat(TableRequest.recognise("позовите официанта")).contains(TableRequest.WAITER);
        assertThat(TableRequest.recognise("можно ускорить горячее?")).contains(TableRequest.SPEED_UP);
        assertThat(TableRequest.recognise("нам нужны приборы")).contains(TableRequest.CUTLERY);
        assertThat(TableRequest.recognise("без хлеба, пожалуйста")).isEmpty();
        assertThat(TableRequest.recognise("хочу ланч")).isEmpty();
    }
}
