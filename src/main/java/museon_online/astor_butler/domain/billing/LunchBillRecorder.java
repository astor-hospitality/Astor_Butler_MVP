package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrderListener;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Opens a bill for every business lunch Butler places and notes what the venue's system answered. */
@Component
@RequiredArgsConstructor
public class LunchBillRecorder implements BusinessLunchOrderListener {

    private final BillingProperties properties;
    private final GuestBillService bills;

    @Override
    public void placed(BusinessLunchService.Request request, BusinessLunchOrder order, ExternalLunchOrderProvider.Result external) {
        if (!properties.isEnabled() || request == null || order == null) {
            return;
        }
        GuestBill bill = bills.open(new BillDraft(
                request.chatId(),
                request.telegramUserId(),
                order.venueCode(),
                GuestBillKind.BUSINESS_LUNCH,
                order.source(),
                order.tableReservationId(),
                "business-lunch:" + order.venueCode(),
                estimate(order)
        ));
        if (external == null || !external.attempted()) {
            return;
        }
        boolean named = external.externalOrderId() != null && !external.externalOrderId().isBlank();
        if (external.accepted() && named) {
            bills.issued(bill.id(), external.providerId(), external.externalOrderId());
        } else {
            bills.venueSubmitFailed(bill.id(), external.providerId(), external.accepted() ? "ACCEPTED_WITHOUT_ID" : external.status());
        }
    }

    /** A set is one priced line for every guest and its dishes ride along; without a set every dish has its own price. */
    static OrderEstimate estimate(BusinessLunchOrder order) {
        List<OrderEstimate.Line> lines = new ArrayList<>();
        boolean set = order.setCode() != null;
        if (set) {
            lines.add(OrderEstimate.Line.priced(order.setCode(), order.setTitle(), order.guests(), order.setPriceRub()));
        }
        for (BusinessLunchOrder.Item item : order.dishes()) {
            lines.add(set
                    ? OrderEstimate.Line.included(item.dishCode(), item.dishTitle(), item.quantity())
                    : OrderEstimate.Line.priced(item.dishCode(), item.dishTitle(), item.quantity(), item.priceRub()));
        }
        return OrderEstimate.of(lines);
    }
}
