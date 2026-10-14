package museon_online.astor_butler.domain.journey;

import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillLine;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * «Как в прошлый раз»: the dishes of the guest's latest lunch bill that are still on today's menu. Dishes that left the
 * menu drop out; nothing left means there is nothing to repeat.
 */
@Component
public class LastLunchService {

    static final int BILLS_LOOKED_AT = 10;

    private final GuestBillRepository bills;

    public LastLunchService(GuestBillRepository bills) {
        this.bills = bills;
    }

    public Optional<LastLunch> find(Long chatId, BusinessLunchOffer offer) {
        if (chatId == null || offer == null) {
            return Optional.empty();
        }
        Optional<GuestBill> latest = bills.findByChatId(chatId, BILLS_LOOKED_AT).stream()
                .filter(bill -> bill.kind() == GuestBillKind.BUSINESS_LUNCH)
                .findFirst();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        List<String> codes = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (GuestBillLine line : bills.findLines(latest.get().id())) {
            Optional<BusinessLunchOffer.Dish> dish = line.itemCode() == null ? Optional.empty() : offer.dish(line.itemCode());
            if (dish.isEmpty()) {
                continue;
            }
            int portions = Math.max(1, line.quantity());
            for (int i = 0; i < portions; i++) {
                codes.add(dish.get().code());
            }
            titles.add(portions > 1 ? dish.get().title() + " × " + portions : dish.get().title());
        }
        return codes.isEmpty() ? Optional.empty() : Optional.of(new LastLunch(codes, titles));
    }
}
