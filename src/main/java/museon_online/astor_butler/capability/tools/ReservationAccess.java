package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whose reservation it is, for the tools that act on one. The Butler's agent may touch only a reservation of its own
 * chat; the Concierge one whose visit is linked to its session (its chat ids are another namespace), or one it made
 * through its booking form for the very guest of this turn: channel {@link TableReservationCommand#CHANNEL_CONCIERGE}
 * or {@link TableReservationCommand#CHANNEL_TELEGRAM} (what POST /api/bookings/table-reservations stores, it takes no
 * channel) and the {@code telegram_user_id} the turn's {@link CapabilityContext#guestTelegramUserId()} names. Anything
 * else is {@code NOT_ALLOWED}, an unknown id {@code NOT_FOUND}.
 */
@Component
public class ReservationAccess {

    /**
     * The channels of a booking the Concierge made itself: its negotiation ({@code CONCIERGE}) or its booking form, whose
     * POST /api/bookings/table-reservations stores the default {@code TELEGRAM}.
     */
    static final Set<String> CONCIERGE_MADE = Set.of(TableReservationCommand.CHANNEL_CONCIERGE,
            TableReservationCommand.CHANNEL_TELEGRAM);

    private final TableReservationRepository orders;
    private final GuestVisitRepository visits;
    private final VisitTurnRepository turns;

    public ReservationAccess(TableReservationRepository orders, GuestVisitRepository visits, VisitTurnRepository turns) {
        this.orders = orders;
        this.visits = visits;
        this.turns = turns;
    }

    /** The reservation when the caller may act on it, else the error to answer with. */
    public record Checked(TableReservationOrder order, CapabilityResult error) {
        public boolean allowed() {
            return error == null;
        }
    }

    /** Reads {@code reservationId} from the arguments and checks it. */
    public Checked check(CapabilityContext ctx, Map<String, Object> args) {
        Integer id;
        try {
            id = CapabilityArguments.integer(args, "reservationId");
        } catch (CapabilityArguments.BadArgument e) {
            return new Checked(null, CapabilityResult.error(CapabilityResult.BAD_ARGUMENT));
        }
        if (id == null || id < 1) {
            return new Checked(null, CapabilityResult.error(CapabilityResult.BAD_ARGUMENT));
        }
        return check(ctx, id.longValue());
    }

    /**
     * Like {@link #check(CapabilityContext, Map)}, but when the given id is missing, unknown or not this guest's, and the
     * Concierge session holds exactly one active booking, that booking is the one meant. Live test 16.10: on «отмените
     * бронь» the model sent reservationId 1 for the guest's booking 9 and the guest heard «отменить нельзя». Not for the
     * lunch order: a lunch must not land in another booking of the same guest.
     */
    public Checked checkOrOnly(CapabilityContext ctx, Map<String, Object> args) {
        Checked direct = check(ctx, args);
        if (direct.allowed() || ctx == null || ctx.butlerAgent() || !CapabilityContext.CONCIERGE.equals(ctx.caller())
                || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return direct;
        }
        List<TableReservationOrder> held = orders.findActiveOrdersOfSession(ctx.sessionId(), ctx.visitId(), null);
        return held != null && held.size() == 1 ? new Checked(held.get(0), null) : direct;
    }

    /**
     * The one booking this guest holds at the venue that is not over yet (its end after {@code now}): the Butler chat's,
     * or the Concierge session's as {@link #checkOrOnly} reads it. Empty with none or several. Live test 16.10: on the
     * guest's «Да» to the lunch the position was empty, no reservationId came, and the guest's booking #68 today at 14:00
     * was there.
     */
    public Optional<TableReservationOrder> onlyUpcoming(CapabilityContext ctx, String venueCode, Instant now) {
        if (ctx == null) {
            return Optional.empty();
        }
        List<TableReservationOrder> held;
        if (ctx.butlerAgent()) {
            held = ctx.chatId() == null ? List.of() : orders.findActiveOrdersByChatId(ctx.chatId(), venueCode);
        } else if (CapabilityContext.CONCIERGE.equals(ctx.caller()) && ctx.sessionId() != null && !ctx.sessionId().isBlank()) {
            held = orders.findActiveOrdersOfSession(ctx.sessionId(), ctx.visitId(), venueCode);
        } else {
            held = List.of();
        }
        List<TableReservationOrder> upcoming = held == null ? List.of() : held.stream()
                .filter(order -> order != null && order.id() != null && order.requestedStartAt() != null)
                .filter(order -> now == null || (order.requestedEndAt() != null ? order.requestedEndAt()
                        : order.requestedStartAt().plus(ReservationCreateTool.VISIT_LENGTH)).isAfter(now))
                .toList();
        return upcoming.size() == 1 ? Optional.of(upcoming.get(0)) : Optional.empty();
    }

    public Checked check(CapabilityContext ctx, long reservationId) {
        Optional<TableReservationOrder> found = orders.findOrder(reservationId);
        if (found.isEmpty()) {
            return new Checked(null, CapabilityResult.error(CapabilityResult.NOT_FOUND));
        }
        TableReservationOrder order = found.get();
        return owns(ctx, order) ? new Checked(order, null) : new Checked(null, CapabilityResult.error(CapabilityResult.NOT_ALLOWED));
    }

    private boolean owns(CapabilityContext ctx, TableReservationOrder order) {
        if (ctx == null) {
            return false;
        }
        if (ctx.butlerAgent()) {
            return ctx.chatId() != null && Objects.equals(ctx.chatId(), order.chatId());
        }
        if (!CapabilityContext.CONCIERGE.equals(ctx.caller())) {
            return false;
        }
        return linkedToSession(ctx, order) || madeForThisGuest(ctx, order);
    }

    private boolean linkedToSession(CapabilityContext ctx, TableReservationOrder order) {
        if (ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return false;
        }
        Optional<GuestVisit> visit = visits.findByReservation(order.id());
        if (visit.isEmpty() || visit.get().id() == null) {
            return false;
        }
        return turns.existsLink(visit.get().id(), ctx.sessionId());
    }

    /**
     * A booking the Concierge's form made for the guest of this turn: «Мои заявки» in its mini app are such bookings,
     * never linked to a session, and «Изменить»/«Отменить» there came back {@code NOT_ALLOWED}. Only with the guest's
     * Telegram user id sent and equal to the booking's; another guest's booking, or one with no user id, stays out.
     */
    private static boolean madeForThisGuest(CapabilityContext ctx, TableReservationOrder order) {
        Long guest = ctx.guestTelegramUserId();
        return guest != null && guest > 0 && guest.equals(order.telegramUserId())
                && order.source() != null && CONCIERGE_MADE.contains(order.source());
    }

    /** The status as the agents read it: the hostess has not answered yet is {@code AWAITING_HOSTESS}. */
    public static String status(TableReservationStatus status) {
        if (status == null) {
            return null;
        }
        return status == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION ? "AWAITING_HOSTESS" : status.name();
    }

    /** A domain refusal as a fact: a bad request, a missing thing, or a conflict with a reason. */
    public static CapabilityResult refusal(ApiException e, String conflictReason) {
        HttpStatus status = e.status();
        if (status == HttpStatus.BAD_REQUEST) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        if (status == HttpStatus.NOT_FOUND) {
            return CapabilityResult.error(CapabilityResult.NOT_FOUND);
        }
        if (status == HttpStatus.CONFLICT) {
            return withReason(CapabilityResult.NOT_ALLOWED, conflictReason);
        }
        return CapabilityResult.error(CapabilityResult.UNAVAILABLE);
    }

    public static CapabilityResult withReason(String error, String reason) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", reason);
        return new CapabilityResult(false, facts, error);
    }
}
