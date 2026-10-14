package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.ProactiveMessage;
import museon_online.astor_butler.domain.journey.UsefulnessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatedHintTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:02:00Z");

    private final ButlerTalkProperties properties = new ButlerTalkProperties();
    private final UsefulnessPolicy policy = mock(UsefulnessPolicy.class);
    private final TableRequestRouter router = mock(TableRequestRouter.class);
    private final SeatedHint.GuestText guest = mock(SeatedHint.GuestText.class);
    private SeatedHint hint;

    private static TableReservationOrder seated() {
        return new TableReservationOrder(42L, 421L, 421L, null, 5L, "13", "Стол 13", null, null, TableReservationStatus.SEATED,
                "TELEGRAM", NOW, NOW.plusSeconds(5400), 2, "Мария", "+79120000000", null, null, null, null, null, NOW, NOW, NOW);
    }

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        hint = new SeatedHint(properties, policy, router, guest);
    }

    @Test
    void seatedHintNamesTheWaiter() {
        when(policy.permits(42L, 421L, ProactiveMessage.SEATED_HINT)).thenReturn(true);
        when(router.waiterName(any())).thenReturn(Optional.of("Кирилл"));

        hint.send(seated());

        verify(guest).send(seated(), "Приятного обеда! Первое блюдо обычно подают через 7–10 минут. "
                + "Ваш официант — Кирилл, если что-то понадобится, просто напишите мне.");
    }

    @Test
    void withoutAWaiterTheHintSkipsTheName() {
        when(policy.permits(42L, 421L, ProactiveMessage.SEATED_HINT)).thenReturn(true);
        when(router.waiterName(any())).thenReturn(Optional.empty());

        hint.send(seated());

        verify(guest).send(seated(), "Приятного обеда! Первое блюдо обычно подают через 7–10 минут. "
                + "Если что-то понадобится, просто напишите мне.");
    }

    @Test
    void silentWhenTheFilterSaysSo() {
        when(policy.permits(42L, 421L, ProactiveMessage.SEATED_HINT)).thenReturn(false);

        hint.send(seated());

        verify(guest, never()).send(any(), anyString());
    }

    @Test
    void silentWhenTalkIsOff() {
        properties.setEnabled(false);

        hint.send(seated());

        verify(guest, never()).send(any(), anyString());
        verify(policy, never()).permits(any(), any(), eq(ProactiveMessage.SEATED_HINT));
    }
}
