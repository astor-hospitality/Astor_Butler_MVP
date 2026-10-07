package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps a guest's bills and the journal of what happened to them. The venue's system is the source of truth for money:
 * a bill is {@code PAID} only because the venue said so, and the sum to pay is the venue's, not Butler's estimate.
 */
@Service
@RequiredArgsConstructor
public class GuestBillService {

    private static final int MAX_BILLS = 50;

    private final GuestBillRepository bills;
    private final BillOperationRepository journal;

    /** Opens the bill for an order, or returns the one already opened for the same reservation. */
    @Transactional
    public GuestBill open(BillDraft draft) {
        if (draft == null || draft.chatId() == null || draft.kind() == null || draft.estimate() == null) {
            throw new IllegalArgumentException("A bill needs a chat, a kind and an estimate");
        }
        if (draft.tableReservationId() != null) {
            Optional<GuestBill> existing = bills.findByReservation(draft.kind(), draft.tableReservationId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        GuestBill bill = bills.insert(draft);
        journal.append(bill.id(), BillOperationType.ESTIMATE_CREATED, true, BillActor.GUEST, null,
                draft.estimate().complete()
                        ? "estimate=" + draft.estimate().totalMinor()
                        : "no estimate: a price is not published");
        return bill;
    }

    /** The venue's system took the order and named it. */
    @Transactional
    public GuestBill issued(Long billId, String externalProvider, String externalOrderId) {
        if (isBlank(externalProvider) || isBlank(externalOrderId)) {
            throw new IllegalArgumentException("An issued bill needs the provider and its order id");
        }
        GuestBill bill = require(billId);
        if (externalProvider.equals(bill.externalProvider()) && externalOrderId.equals(bill.externalOrderId())) {
            return bill;
        }
        if (bill.status() != GuestBillStatus.ESTIMATED) {
            throw new IllegalStateException("Bill " + billId + " is " + bill.status() + " and cannot be issued again");
        }
        GuestBill updated = bills.attachExternalOrder(billId, externalProvider, externalOrderId);
        journal.append(billId, BillOperationType.ISSUED_TO_VENUE, true, BillActor.SYSTEM, null, "provider=" + externalProvider);
        return updated;
    }

    /** The venue's system did not take the order; the bill stays an estimate and the staff enter the order by hand. */
    @Transactional
    public void venueSubmitFailed(Long billId, String externalProvider, String errorCode) {
        require(billId);
        journal.append(billId, BillOperationType.VENUE_SUBMIT_FAILED, false, BillActor.SYSTEM, errorCode,
                isBlank(externalProvider) ? null : "provider=" + externalProvider);
    }

    /**
     * Mirrors what the venue's system reports for its order. Call it only with an answer that was read:
     * a failed call is not a report. An unrecognised pay state is stored as {@code UNKNOWN} and never counts as paid.
     *
     * @param venueAmountMinor the venue's sum in kopecks; null keeps the last known one
     * @return the bill, or empty when Butler holds no bill for this order
     */
    @Transactional
    public Optional<GuestBill> venueReported(String externalProvider, String externalOrderId, BillPayState payState, Long venueAmountMinor) {
        Optional<GuestBill> found = bills.findByExternalOrder(externalProvider, externalOrderId);
        if (found.isEmpty()) {
            return found;
        }
        GuestBill bill = found.get();
        BillPayState state = payState == null ? BillPayState.UNKNOWN : payState;
        Long amount = venueAmountMinor == null || venueAmountMinor < 0 ? bill.venueAmountMinor() : venueAmountMinor;
        boolean stateChanged = state != bill.payState();
        boolean amountChanged = !Objects.equals(amount, bill.venueAmountMinor());
        if (!stateChanged && !amountChanged) {
            return found;
        }

        GuestBillStatus status = bill.status() == GuestBillStatus.CANCELLED
                ? GuestBillStatus.CANCELLED
                : state.settled() ? GuestBillStatus.PAID : GuestBillStatus.ISSUED;
        GuestBill updated = bills.updateVenueState(bill.id(), status, state, amount);
        if (stateChanged) {
            journal.append(bill.id(), BillOperationType.PAY_STATE_CHANGED, true, BillActor.VENUE, null, bill.payState() + " -> " + state);
        }
        if (amountChanged) {
            journal.append(bill.id(), BillOperationType.VENUE_AMOUNT_REPORTED, true, BillActor.VENUE, null, "venue=" + amount);
            if (bill.estimateMinor() != null && !bill.estimateMinor().equals(amount)) {
                journal.append(bill.id(), BillOperationType.AMOUNT_MISMATCH, true, BillActor.VENUE, null,
                        "estimate=" + bill.estimateMinor() + ", venue=" + amount);
            }
        }
        return Optional.of(updated);
    }

    /** A paid bill is the venue's to refund, so it cannot be cancelled here. */
    @Transactional
    public GuestBill cancel(Long billId, BillActor actor, String reason) {
        GuestBill bill = require(billId);
        if (bill.status() == GuestBillStatus.CANCELLED) {
            return bill;
        }
        if (bill.status() == GuestBillStatus.PAID) {
            throw new IllegalStateException("Bill " + billId + " is paid; a refund is made in the venue's system");
        }
        GuestBill updated = bills.updateStatus(billId, GuestBillStatus.CANCELLED);
        journal.append(billId, BillOperationType.CANCELLED, true, actor, null, reason);
        return updated;
    }

    /** The guest's bills, newest first. */
    public List<GuestBill> billsOf(Long chatId, int limit) {
        if (chatId == null) {
            return List.of();
        }
        return bills.findByChatId(chatId, Math.max(1, Math.min(limit, MAX_BILLS)));
    }

    public List<GuestBillLine> linesOf(Long billId) {
        return bills.findLines(billId);
    }

    public List<BillOperation> historyOf(Long billId) {
        return journal.findByBill(billId);
    }

    private GuestBill require(Long billId) {
        return bills.find(billId).orElseThrow(() -> new IllegalArgumentException("Unknown bill: " + billId));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
