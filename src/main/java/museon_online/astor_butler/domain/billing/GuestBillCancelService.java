package museon_online.astor_butler.domain.billing;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Cancels the bill of a reservation: on the guest's own «отмените счёт» (the agents' {@code bill_cancel}) or by the
 * hostess «Отменить счёт» on the bill card. A paid bill is never cancelled here (a refund is made at the venue's till);
 * a cancelled one is left as it is. Once cancelled, the staff group's wishes topic gets one line «Стол N: счёт отменён».
 */
@Service
@Slf4j
public class GuestBillCancelService {

    /** What came of the request; the caller words it for whoever asked. */
    public enum Outcome {
        /** {@code ASTOR_BILLING_ENABLED} is off. */
        BILLING_OFF,
        /** No bill for the reservation in Butler. */
        NO_BILL,
        /** The bill is paid: refused, the refund is made at the venue's till. */
        PAID,
        /** The bill was cancelled before; nothing changed. */
        ALREADY_CANCELLED,
        /** The bill is cancelled now. */
        CANCELLED
    }

    public record Result(Outcome outcome, Long billId) {
    }

    private final BillingProperties properties;
    private final GuestBillService bills;
    private final TableReservationRepository reservations;

    /** The staff group's line: optional so the service and its tests stand without Telegram. */
    private GuestBillStaffCard staffCard;

    public GuestBillCancelService(BillingProperties properties, GuestBillService bills, TableReservationRepository reservations) {
        this.properties = properties;
        this.bills = bills;
        this.reservations = reservations;
    }

    @Autowired(required = false)
    public void setStaffCard(GuestBillStaffCard staffCard) {
        this.staffCard = staffCard;
    }

    public Result cancelByReservation(long reservationId, BillActor actor, String reason) {
        if (!properties.isEnabled()) {
            return new Result(Outcome.BILLING_OFF, null);
        }
        Optional<GuestBill> found = bills.findByReservation(reservationId);
        if (found.isEmpty()) {
            return new Result(Outcome.NO_BILL, null);
        }
        GuestBill bill = found.get();
        if (bill.status() == GuestBillStatus.CANCELLED) {
            return new Result(Outcome.ALREADY_CANCELLED, bill.id());
        }
        if (bill.status() == GuestBillStatus.PAID) {
            return new Result(Outcome.PAID, bill.id());
        }
        try {
            bills.cancel(bill.id(), actor, reason);
        } catch (IllegalStateException e) {
            // Paid between the read and the cancel.
            return new Result(Outcome.PAID, bill.id());
        }
        log.info("Bill {} of reservation {} cancelled by {}", bill.id(), reservationId, actor);
        if (staffCard != null) {
            Optional<TableReservationOrder> order = reservations.findOrder(reservationId);
            order.ifPresent(staffCard::billCancelled);
        }
        return new Result(Outcome.CANCELLED, bill.id());
    }
}
