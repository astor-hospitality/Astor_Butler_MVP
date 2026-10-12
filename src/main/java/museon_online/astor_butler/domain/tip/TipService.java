package museon_online.astor_butler.domain.tip;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TipService {

    private final TipRepository repository;

    public List<StaffProfile> listActiveStaff(String venueCode) {
        return repository.findActiveStaff(venueCode);
    }

    public TipOrder getOrder(Long id) {
        return requireOrder(id);
    }

    public List<TipOrder> listOrdersByChatId(Long chatId, int limit) {
        if (chatId == null) {
            throw badRequest("chatId is required");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findOrdersByChatId(chatId, safeLimit);
    }

    @Transactional
    public TipOrder createDraft(TipOrderCommand command) {
        validateCommand(command);
        StaffProfile staff = resolveStaff(command);
        return repository.createDraft(command, staff);
    }

    @Transactional
    public TipOrder confirmLatestDraft(Long chatId) {
        TipOrder order = requireLatestAwaitingConfirmation(chatId);
        return confirmDraft(order.id());
    }

    @Transactional
    public TipOrder cancelLatestDraft(Long chatId) {
        TipOrder order = requireLatestAwaitingConfirmation(chatId);
        return cancelDraft(order.id());
    }

    /** The guest changed the size of a tip they have not confirmed yet: the same thanks, a new sum. */
    @Transactional
    public TipOrder changeDraftAmount(Long id, long amountMinor, String guestComment) {
        if (amountMinor < 1) {
            throw badRequest("amountMinor must be positive");
        }
        TipOrder order = requireAwaitingConfirmation(id);
        return repository.updateAmount(order.id(), amountMinor, guestComment);
    }

    @Transactional
    public TipOrder confirmDraft(Long id) {
        TipOrder order = requireAwaitingConfirmation(id);
        return repository.updateStatus(order.id(), TipOrderStatus.AWAITING_PAYMENT);
    }

    @Transactional
    public TipOrder cancelDraft(Long id) {
        TipOrder order = requireAwaitingConfirmation(id);
        return repository.updateStatus(order.id(), TipOrderStatus.CANCELLED);
    }

    /** A waiter got the tip by the venue's QR or terminal. Butler takes no money; this only records it. */
    @Transactional
    public TipOrder markPaid(Long id) {
        TipOrder order = requireOrder(id);
        if (order.status() == TipOrderStatus.PAID) {
            return order;
        }
        if (order.status() != TipOrderStatus.AWAITING_PAYMENT) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.CONFLICT,
                    "Only confirmed tips awaiting payment can be marked paid",
                    Map.of("id", id, "status", order.status())
            );
        }
        return repository.updateStatus(order.id(), TipOrderStatus.PAID);
    }

    /** The recipient's own SBP link, once the venue has given one. */
    public Optional<String> sbpLinkOf(TipOrder order) {
        if (order == null || order.staffId() == null) {
            return Optional.empty();
        }
        return repository.findStaff(order.staffId())
                .map(StaffProfile::sbpLink)
                .map(String::trim)
                .filter(link -> !link.isEmpty());
    }

    /** Which link the guest was given to pay the tip by. */
    @Transactional
    public TipOrder attachSbpUrl(Long id, String sbpUrl) {
        return repository.updateSbpUrl(id, sbpUrl);
    }

    private StaffProfile resolveStaff(TipOrderCommand command) {
        if (command.staffId() != null) {
            return repository.findStaff(command.staffId())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            ErrorCode.NOT_FOUND,
                            "Staff profile was not found",
                            Map.of("staffId", command.staffId())
                    ));
        }
        return repository.findDefaultStaff(command.venueCode()).orElse(null);
    }

    private TipOrder requireOrder(Long id) {
        if (id == null) {
            throw badRequest("tip order id is required");
        }
        return repository.findOrder(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.NOT_FOUND,
                        "Tip order was not found",
                        Map.of("id", id)
                ));
    }

    private TipOrder requireLatestAwaitingConfirmation(Long chatId) {
        if (chatId == null) {
            throw badRequest("chatId is required");
        }
        return repository.findLatestAwaitingGuestConfirmation(chatId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.NOT_FOUND,
                        "Active tip draft was not found",
                        Map.of("chatId", chatId)
                ));
    }

    private TipOrder requireAwaitingConfirmation(Long id) {
        TipOrder order = requireOrder(id);
        if (order.status() != TipOrderStatus.AWAITING_GUEST_CONFIRMATION) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.CONFLICT,
                    "Only awaiting guest confirmation tip drafts can be changed",
                    Map.of("id", id, "status", order.status())
            );
        }
        return order;
    }

    private void validateCommand(TipOrderCommand command) {
        if (command == null) {
            throw badRequest("Request body is required");
        }
        if (command.chatId() == null) {
            throw badRequest("chatId is required");
        }
        if (command.amountMinor() != null && command.amountMinor() < 1) {
            throw badRequest("amountMinor must be positive");
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }
}
