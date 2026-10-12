package museon_online.astor_butler.domain.tip;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What happens after the guest confirms a tip. Butler takes no money: in the semi-automatic mode the waiters get a
 * card and come with a QR or a terminal, in the automatic mode the guest gets the recipient's SBP link. A waiter marks
 * a carded tip received with the card's button.
 */
@Service
@Slf4j
public class TipHandoffService {

    static final String CARD_SENT = "Спасибо! Передал команде — официант подойдёт с QR.";
    static final String CARD_NOT_SENT = "Спасибо! Чтобы оставить чаевые, попросите официанта подойти с QR.";

    /** A visit that started this long ago may still be at its table: a long dinner, a banquet. */
    private static final Duration VISIT_LENGTH = Duration.ofHours(6);
    /** A guest may come and pay before the time they booked. */
    private static final Duration EARLY_ARRIVAL = Duration.ofHours(1);
    private static final Pattern STAFF_CALLBACK = Pattern.compile("^" + TipStaffNotifier.CALLBACK_PREFIX + "(\\d{1,18})$");

    private final TipProperties properties;
    private final TipService tips;
    private final TableReservationRepository reservations;
    private final TipStaffNotifier staff;
    private final Clock clock;

    @Autowired
    public TipHandoffService(TipProperties properties, TipService tips, TableReservationRepository reservations, TipStaffNotifier staff) {
        this(properties, tips, reservations, staff, Clock.systemUTC());
    }

    TipHandoffService(TipProperties properties, TipService tips, TableReservationRepository reservations, TipStaffNotifier staff, Clock clock) {
        this.properties = properties;
        this.tips = tips;
        this.reservations = reservations;
        this.staff = staff;
        this.clock = clock;
    }

    /**
     * @param guestText what the guest is told
     * @param way       STAFF_CARD, STAFF_CARD_NOT_DELIVERED or SBP_LINK
     */
    public record Handoff(String guestText, String way) {
    }

    /**
     * @param handled    whether the button was a tip's
     * @param answerText the short answer the waiter sees
     * @param doneLabel  the card's button becomes this once the tip is marked; null leaves the card as it is
     */
    public record StaffCallbackResult(boolean handled, String answerText, String doneLabel) {

        static StaffCallbackResult notHandled() {
            return new StaffCallbackResult(false, null, null);
        }

        static StaffCallbackResult answer(String text) {
            return new StaffCallbackResult(true, text, null);
        }
    }

    /** The tip is confirmed and waits for its money. Never fails: the guest has said yes and gets an answer. */
    public Handoff afterConfirmed(TipOrder order, TipGuest guest) {
        if (properties.getMode() == TipMode.SBP_LINK) {
            Optional<String> link = sbpLinkOf(order);
            if (link.isPresent()) {
                keepSbpUrl(order, link.get());
                return new Handoff("Спасибо! Перевести %s можно по СБП: %s".formatted(sum(order), link.get()), TipMode.SBP_LINK.name());
            }
            log.info("Tip #{} has no SBP link of its recipient, the waiters get the card", order.id());
        }
        boolean sent = staff.tipCard(order, guest, visitOf(guest));
        return sent
                ? new Handoff(CARD_SENT, TipMode.STAFF_CARD.name())
                : new Handoff(CARD_NOT_SENT, "STAFF_CARD_NOT_DELIVERED");
    }

    /** «Чаевые получены» on a waiters' card. Only the staff chats may press it; pressing twice changes nothing. */
    public StaffCallbackResult handleStaffCallback(String data, Long chatId, String staffName) {
        Matcher matcher = data == null ? null : STAFF_CALLBACK.matcher(data);
        if (matcher == null || !matcher.matches()) {
            return StaffCallbackResult.notHandled();
        }
        if (!staff.isStaffChat(chatId)) {
            return StaffCallbackResult.answer("Эта кнопка — для рабочего чата");
        }
        long id = Long.parseLong(matcher.group(1));
        TipOrder order;
        try {
            order = tips.getOrder(id);
        } catch (RuntimeException e) {
            return StaffCallbackResult.answer("Чаевые не найдены");
        }
        if (order.status() == TipOrderStatus.PAID) {
            return StaffCallbackResult.answer("Уже отмечено");
        }
        if (order.status() != TipOrderStatus.AWAITING_PAYMENT) {
            return StaffCallbackResult.answer("Эти чаевые не ждут оплаты");
        }
        try {
            tips.markPaid(id);
        } catch (RuntimeException e) {
            log.info("Tip #{} could not be marked paid: {}", id, e.getMessage());
            return StaffCallbackResult.answer("Эти чаевые не ждут оплаты");
        }
        log.info("Tip #{} marked paid from the staff chat {}", id, chatId);
        String name = staffName == null || staffName.isBlank() ? null : staffName.trim();
        return new StaffCallbackResult(true, "Отмечено: чаевые #%d получены".formatted(id), name == null ? "✅ Получено" : "✅ Получено — " + name);
    }

    private Optional<String> sbpLinkOf(TipOrder order) {
        try {
            return tips.sbpLinkOf(order);
        } catch (RuntimeException e) {
            log.warn("SBP link of tip #{} could not be read: {}", order.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private void keepSbpUrl(TipOrder order, String link) {
        try {
            tips.attachSbpUrl(order.id(), link);
        } catch (RuntimeException e) {
            // The guest still gets the link; only the record of which link was given is missing.
            log.warn("SBP link was not kept with tip #{}: {}", order.id(), e.getMessage());
        }
    }

    /** Today's reservation of the guest: the latest that has started, else one about to start. */
    private TipVisit visitOf(TipGuest guest) {
        if (guest == null || guest.chatId() == null) {
            return null;
        }
        Instant now = clock.instant();
        List<TableReservationOrder> active;
        try {
            active = reservations.findActiveOrdersByChatId(guest.chatId());
        } catch (RuntimeException e) {
            log.warn("Reservation for the tip card could not be read: chatId={}, reason={}", guest.chatId(), e.getMessage());
            return null;
        }
        Optional<TableReservationOrder> started = active.stream()
                .filter(reservation -> reservation.requestedStartAt() != null)
                .filter(reservation -> !reservation.requestedStartAt().isAfter(now)
                        && reservation.requestedStartAt().isAfter(now.minus(VISIT_LENGTH)))
                .max(Comparator.comparing(TableReservationOrder::requestedStartAt));
        return started
                .or(() -> active.stream()
                        .filter(reservation -> reservation.requestedStartAt() != null)
                        .filter(reservation -> reservation.requestedStartAt().isAfter(now)
                                && !reservation.requestedStartAt().isAfter(now.plus(EARLY_ARRIVAL)))
                        .min(Comparator.comparing(TableReservationOrder::requestedStartAt)))
                .map(reservation -> new TipVisit(table(reservation), reservation.id()))
                .orElse(null);
    }

    private static String table(TableReservationOrder reservation) {
        if (reservation.tableDisplayName() != null && !reservation.tableDisplayName().isBlank()) {
            return reservation.tableDisplayName().trim();
        }
        return reservation.tableCode() == null || reservation.tableCode().isBlank() ? null : reservation.tableCode().trim();
    }

    private static String sum(TipOrder order) {
        return order.amountMinor() == null ? "чаевые" : TipMoney.rubles(order.amountMinor()) + " ₽";
    }
}
