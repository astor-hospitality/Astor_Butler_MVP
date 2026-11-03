package museon_online.astor_butler.domain.auction;

import museon_online.astor_butler.api.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtAuctionServiceTest {

    @Mock
    private ArtAuctionRepository repository;

    @InjectMocks
    private ArtAuctionService service;

    @Test
    void rejectsBidOnDraftLotEvenWhenLotIdIsKnown() {
        ArtAuctionLot draft = lot(42L, ArtAuctionLotStatus.DRAFT, null);
        when(repository.findLot(42L)).thenReturn(Optional.of(draft));
        when(repository.findBiddableLot(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createBidDraft(command(42L, 500_000L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not open for bids");
        verify(repository, never()).createBidDraft(any(), any());
    }

    @Test
    void acceptsBidDraftOnActiveLotById() {
        ArtAuctionLot active = lot(42L, ArtAuctionLotStatus.ACTIVE, 100_000L);
        ArtAuctionBidCommand command = command(42L, 500_000L);
        ArtAuctionBid bid = new ArtAuctionBid(7L, 42L, 1L, null, null, ArtAuctionBidStatus.AWAITING_GUEST_CONFIRMATION,
                "TELEGRAM", 500_000L, "RUB", null, null, null, Instant.now(), Instant.now());
        when(repository.findLot(42L)).thenReturn(Optional.of(active));
        when(repository.findBiddableLot(42L)).thenReturn(Optional.of(active));
        when(repository.createBidDraft(command, active)).thenReturn(bid);

        assertThat(service.createBidDraft(command)).isEqualTo(bid);
    }

    @Test
    void returnsNotFoundForUnknownLot() {
        when(repository.findLot(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createBidDraft(command(99L, 500_000L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("was not found");
    }

    private static ArtAuctionBidCommand command(Long lotId, Long amountMinor) {
        return new ArtAuctionBidCommand(1L, null, null, "AERIS", lotId, amountMinor, "RUB", null, null);
    }

    private static ArtAuctionLot lot(Long id, ArtAuctionLotStatus status, Long startingPriceMinor) {
        return new ArtAuctionLot(id, 5L, "LOT", "Title", "Artist", null, status, startingPriceMinor, null,
                "RUB", null, "{}", Instant.now(), Instant.now());
    }
}
