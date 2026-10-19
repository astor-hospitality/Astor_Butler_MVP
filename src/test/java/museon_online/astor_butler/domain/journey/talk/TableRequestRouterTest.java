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
import static org.mockito.Mockito.never;
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
        when(waiters.forRequest("13", LocalDate.of(2026, 10, 14))).thenReturn(new TableRequestRouter.Addressee("Кирилл", "VIP_ZONE"));

        assertThat(router.route(42L, TableRequest.BREAD, "можно ещё хлеба")).isEqualTo("Передал. Кирилл скоро подойдёт.");
        verify(staffChat).send(eq(order()), eq(TableRequest.BREAD), eq("можно ещё хлеба"),
                eq(new TableRequestRouter.Addressee("Кирилл", "VIP_ZONE")));
    }

    @Test
    void noWaiterInTheZoneGoesToTheTeamWithTheZoneNamed() {
        when(reservations.find(42L)).thenReturn(Optional.of(order()));
        when(waiters.forRequest(any(), any())).thenReturn(TableRequestRouter.Addressee.team("VIP_ZONE"));

        TableRequestRouter.Routed routed = router.deliver(42L, TableRequest.WATER, "можно воды");

        assertThat(routed.answer()).isEqualTo("Передал команде, скоро подойдут.");
        assertThat(routed.delivered()).isTrue();
        assertThat(routed.waiterName()).isNull();
        verify(staffChat).send(eq(order()), eq(TableRequest.WATER), eq("можно воды"), eq(TableRequestRouter.Addressee.team("VIP_ZONE")));
    }

    @Test
    void theWaiterOfTheRequestIsNamedInTheResult() {
        when(reservations.find(42L)).thenReturn(Optional.of(order()));
        when(waiters.forRequest("13", LocalDate.of(2026, 10, 14))).thenReturn(new TableRequestRouter.Addressee("Даниил", "VIP_ZONE"));

        TableRequestRouter.Routed routed = router.deliver(42L, TableRequest.TAKEAWAY, "упакуйте, пожалуйста, с собой");

        assertThat(routed).isEqualTo(new TableRequestRouter.Routed("Передал. Даниил скоро подойдёт.", true, "Даниил"));
    }

    @Test
    void theSeatedHintAndTheTipsReadTheAssignmentWithoutPickingAnyone() {
        when(waiters.nameOf("13", LocalDate.of(2026, 10, 14))).thenReturn(Optional.empty());

        assertThat(router.waiterName(order())).isEmpty();
        verify(waiters, never()).forRequest(any(), any());
    }

    @Test
    void aWaitersLookupWithoutZonesStillAnswersByTheAssignment() {
        TableRequestRouter.Waiters onlyAssignments = (tableCode, day) -> Optional.of("Кирилл");
        TableRequestRouter plain = new TableRequestRouter(reservations, onlyAssignments, staffChat,
                Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Yekaterinburg");
        when(reservations.find(42L)).thenReturn(Optional.of(order()));

        assertThat(plain.route(42L, TableRequest.CUTLERY, "нам нужны салфетки")).isEqualTo("Передал. Кирилл скоро подойдёт.");
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
        assertThat(TableRequest.recognise("нам нужны салфетки")).contains(TableRequest.CUTLERY);
        assertThat(TableRequest.recognise("упакуйте, пожалуйста, остатки")).contains(TableRequest.TAKEAWAY);
        assertThat(TableRequest.recognise("можно забрать пасту с собой?")).contains(TableRequest.TAKEAWAY);
        assertThat(TableRequest.recognise("можно с собой?")).contains(TableRequest.TAKEAWAY);
        assertThat(TableRequest.recognise("у меня с собой торт")).isEmpty();
    }
}
