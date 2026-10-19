package museon_online.astor_butler.domain.tip;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.domain.tips.TipRecipient;
import museon_online.astor_butler.domain.tips.TipRecipientResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TipService {

    private final TipRepository repository;

    /**
     * The waiter of the guest's table as the recipient (#161, {@code astor.tips.personal.enabled}). Optional so that
     * {@code new TipService(repository)} keeps working; absent or off, the venue's default recipient is used as before.
     */
    @Autowired(required = false)
    private TipRecipientResolver recipientResolver;

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
        TipRecipient personal = personalRecipient(command);
        if (personal != null) {
            // The waiter of the table, by name and own link: no staff_profiles row stands behind them.
            return repository.createDraft(withRecipientName(command, personal.name()), null, personal.link());
        }
        StaffProfile staff = resolveStaff(command);
        return repository.createDraft(command, staff);
    }

    /**
     * A draft for the tip at the table of a reservation (the agents' {@code tip_create}): the recipient is the waiter
     * of that table when personal tips are on, else as in {@link #createDraft}. The reservation, not a chat, says
     * where the guest sits, so the draft may have no chat: a guest who booked through the Concierge has none here.
     */
    @Transactional
    public TipOrder createDraftFor(museon_online.astor_butler.domain.booking.TableReservationOrder reservation, TipOrderCommand command) {
        if (reservation == null || command == null) {
            throw badRequest("Reservation and request body are required");
        }
        if (command.amountMinor() == null || command.amountMinor() < 1) {
            throw badRequest("amountMinor must be positive");
        }
        if (command.staffId() == null && recipientResolver != null && recipientResolver.enabled()) {
            TipRecipient recipient = recipientResolver.forOrder(reservation, command.venueCode());
            if (recipient != null && recipient.personal()) {
                return repository.createDraft(withRecipientName(command, recipient.name()), null, recipient.link());
            }
        }
        return repository.createDraft(command, resolveStaff(command));
    }

    /** The waiter of the guest's table today, when the feature is on and the caller did not name a recipient. */
    private TipRecipient personalRecipient(TipOrderCommand command) {
        if (command.staffId() != null || recipientResolver == null || !recipientResolver.enabled()) {
            return null;
        }
        TipRecipient recipient = recipientResolver.forChat(command.chatId(), command.venueCode());
        return recipient != null && recipient.personal() ? recipient : null;
    }

    private static TipOrderCommand withRecipientName(TipOrderCommand command, String name) {
        return new TipOrderCommand(
                command.chatId(),
                command.telegramUserId(),
                command.userId(),
                command.venueCode(),
                null,
                name,
                command.amountMinor(),
                command.currency(),
                command.guestName(),
                command.guestComment()
        );
    }

    /** For tests and manual wiring: Spring sets the field itself. */
    public void setRecipientResolver(TipRecipientResolver recipientResolver) {
        this.recipientResolver = recipientResolver;
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
