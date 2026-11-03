package museon_online.astor_butler.domain.auction;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ArtAuctionService {

    private final ArtAuctionRepository repository;

    public List<ArtAuctionLot> listActiveLots(String venueCode) {
        return repository.findActiveLots(venueCode);
    }

    public ArtAuctionBid getBid(Long id) {
        return requireBid(id);
    }

    public List<ArtAuctionBid> listBidsByChatId(Long chatId, int limit) {
        if (chatId == null) {
            throw badRequest("chatId is required");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findBidsByChatId(chatId, safeLimit);
    }

    @Transactional
    public ArtAuctionBid createBidDraft(ArtAuctionBidCommand command) {
        validateCommand(command);
        ArtAuctionLot lot = resolveLot(command);
        validateAmountAgainstLot(command, lot);
        return repository.createBidDraft(command, lot);
    }

    @Transactional
    public ArtAuctionBid confirmLatestBidDraft(Long chatId) {
        ArtAuctionBid bid = requireLatestAwaitingConfirmation(chatId);
        return confirmBidDraft(bid.id());
    }

    @Transactional
    public ArtAuctionBid cancelLatestBidDraft(Long chatId) {
        ArtAuctionBid bid = requireLatestAwaitingConfirmation(chatId);
        return cancelBidDraft(bid.id());
    }

    @Transactional
    public ArtAuctionBid confirmBidDraft(Long id) {
        ArtAuctionBid bid = requireAwaitingGuestConfirmation(id);
        return repository.updateBidStatus(bid.id(), ArtAuctionBidStatus.AWAITING_MANAGER_VALIDATION);
    }

    @Transactional
    public ArtAuctionBid cancelBidDraft(Long id) {
        ArtAuctionBid bid = requireAwaitingGuestConfirmation(id);
        return repository.updateBidStatus(bid.id(), ArtAuctionBidStatus.CANCELLED);
    }

    private ArtAuctionLot resolveLot(ArtAuctionBidCommand command) {
        if (command.lotId() != null) {
            ArtAuctionLot lot = repository.findLot(command.lotId())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            ErrorCode.NOT_FOUND,
                            "Art auction lot was not found",
                            Map.of("lotId", command.lotId())
                    ));
            // A lot prepared as DRAFT (or under a DRAFT/closed event) must not take bids
            // until a manager opens it, even when a guest knows its id.
            return repository.findBiddableLot(lot.id())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.CONFLICT,
                            ErrorCode.CONFLICT,
                            "Art auction lot is not open for bids",
                            Map.of("lotId", lot.id(), "status", lot.status())
                    ));
        }
        return repository.findDefaultActiveLot(command.venueCode())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCode.CONFLICT,
                        "No active art auction lot is available",
                        Map.of("venueCode", command.venueCode() == null ? "AERIS" : command.venueCode())
                ));
    }

    private ArtAuctionBid requireBid(Long id) {
        if (id == null) {
            throw badRequest("auction bid id is required");
        }
        return repository.findBid(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.NOT_FOUND,
                        "Art auction bid was not found",
                        Map.of("id", id)
                ));
    }

    private ArtAuctionBid requireLatestAwaitingConfirmation(Long chatId) {
        if (chatId == null) {
            throw badRequest("chatId is required");
        }
        return repository.findLatestAwaitingGuestConfirmation(chatId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.NOT_FOUND,
                        "Active auction bid draft was not found",
                        Map.of("chatId", chatId)
                ));
    }

    private ArtAuctionBid requireAwaitingGuestConfirmation(Long id) {
        ArtAuctionBid bid = requireBid(id);
        if (bid.status() != ArtAuctionBidStatus.AWAITING_GUEST_CONFIRMATION) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.CONFLICT,
                    "Only awaiting guest confirmation auction bid drafts can be changed",
                    Map.of("id", id, "status", bid.status())
            );
        }
        return bid;
    }

    private void validateCommand(ArtAuctionBidCommand command) {
        if (command == null) {
            throw badRequest("Request body is required");
        }
        if (command.chatId() == null) {
            throw badRequest("chatId is required");
        }
        if (command.amountMinor() == null || command.amountMinor() < 1) {
            throw badRequest("amountMinor must be positive");
        }
    }

    private void validateAmountAgainstLot(ArtAuctionBidCommand command, ArtAuctionLot lot) {
        if (lot.startingPriceMinor() != null && command.amountMinor() < lot.startingPriceMinor()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.CONFLICT,
                    "Bid amount is below lot starting price",
                    Map.of("amountMinor", command.amountMinor(), "startingPriceMinor", lot.startingPriceMinor())
            );
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }
}
