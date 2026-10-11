package museon_online.astor_butler.domain.tip;

import museon_online.astor_butler.api.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A guest who presses "Изменить размер чаевых" keeps the same thanks number: the draft's sum is rewritten in place. */
@ExtendWith(MockitoExtension.class)
class TipServiceTest {

    @Mock
    private TipRepository repository;

    @Test
    void rewritesTheSumOfADraftTheGuestHasNotConfirmedYet() {
        TipService service = new TipService(repository);
        when(repository.findOrder(55L)).thenReturn(Optional.of(order(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, 24_000L)));
        when(repository.updateAmount(55L, 36_000L, "15% от 2400 ₽")).thenReturn(order(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, 36_000L));

        TipOrder changed = service.changeDraftAmount(55L, 36_000L, "15% от 2400 ₽");

        assertThat(changed.id()).isEqualTo(55L);
        assertThat(changed.amountMinor()).isEqualTo(36_000L);
    }

    @Test
    void doesNotTouchADraftThatIsAlreadyConfirmedOrCancelled() {
        TipService service = new TipService(repository);
        when(repository.findOrder(55L)).thenReturn(Optional.of(order(TipOrderStatus.AWAITING_PAYMENT, 24_000L)));

        assertThatThrownBy(() -> service.changeDraftAmount(55L, 36_000L, "15%")).isInstanceOf(ApiException.class);
        verify(repository, never()).updateAmount(anyLong(), anyLong(), anyString());
    }

    @Test
    void aNewSumHasToBePositive() {
        TipService service = new TipService(repository);

        assertThatThrownBy(() -> service.changeDraftAmount(55L, 0L, "0")).isInstanceOf(ApiException.class);
        verify(repository, never()).findOrder(anyLong());
    }

    private static TipOrder order(TipOrderStatus status, long amountMinor) {
        return new TipOrder(55L, 1773317437L, 1773317437L, null, "AERIS", 1L, "Команда AERIS", status, "TELEGRAM",
                amountMinor, "RUB", "Наталья", "10% от 2400 ₽", null, null,
                Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"));
    }
}
