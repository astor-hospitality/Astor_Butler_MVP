package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import museon_online.astor_butler.domain.tip.TipOrder;
import museon_online.astor_butler.domain.tip.TipOrderCommand;
import museon_online.astor_butler.domain.tip.TipService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code tip_create{reservationId, amountRub}}: a tip draft for the waiter of the guest's table, 1 to 20 000 rubles.
 * The guest still confirms the draft and pays it; no waiter assigned to the table is {@code NOT_FOUND}.
 */
@Component
public class TipCreateTool implements CapabilityTool {

    static final int MAX_RUB = 20_000;

    private final TipService tips;
    private final TableRequestRouter router;
    private final ReservationAccess access;
    private final String venueCode;

    public TipCreateTool(TipService tips, TableRequestRouter router, ReservationAccess access) {
        this(tips, router, access, new CapabilityProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TipCreateTool(TipService tips, TableRequestRouter router, ReservationAccess access, CapabilityProperties properties) {
        this.tips = tips;
        this.router = router;
        this.access = access;
        this.venueCode = properties.getVenueCode();
    }

    @Override
    public String name() {
        return "tip_create";
    }

    @Override
    public String description() {
        return "Подготовить чаевые официанту стола гостя: сумма в рублях от 1 до 20000. Создаётся черновик, гость "
                + "подтверждает и оплачивает его сам. Возвращает номер черновика (draftId) и имя официанта (waiter).";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reservationId", Map.of("type", "integer", "description", "Номер брони гостя"));
        props.put("amountRub", Map.of("type", "integer", "minimum", 1, "maximum", MAX_RUB, "description", "Сумма чаевых в рублях"));
        return Map.of("type", "object", "properties", props, "required", List.of("reservationId", "amountRub"));
    }

    @Override
    public boolean commitment() {
        return true;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        Integer amount = CapabilityArguments.integer(args, "amountRub");
        if (amount == null || amount < 1 || amount > MAX_RUB) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        ReservationAccess.Checked checked = access.check(ctx, args);
        if (!checked.allowed()) {
            return checked.error();
        }
        TableReservationOrder order = checked.order();
        Optional<String> waiter = router.waiterName(order);
        if (waiter.isEmpty()) {
            return ReservationAccess.withReason(CapabilityResult.NOT_FOUND, "no_waiter");
        }
        // A Concierge booking has no chat of the Butler's bot: the draft is made for the reservation without one.
        TipOrder draft = tips.createDraftFor(order, new TipOrderCommand(order.chatId(), order.telegramUserId(), null, venueCode, null,
                waiter.get(), amount * 100L, "RUB", order.guestName(), null));
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("draftId", draft.id());
        // The recipient the draft names: the table's waiter, or the venue's default one while personal tips are off.
        facts.put("waiter", draft.staffDisplayName() == null || draft.staffDisplayName().isBlank() ? waiter.get() : draft.staffDisplayName());
        return CapabilityResult.ok(facts);
    }
}
