package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Whose reservation it is, for the tools that act on one. The Butler's agent may touch only a reservation of its own
 * chat; the Concierge only one whose visit is linked to its session (its chat ids are another namespace). Anything
 * else is {@code NOT_ALLOWED}, an unknown id {@code NOT_FOUND}.
 */
@Component
public class ReservationAccess {

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
        if (!CapabilityContext.CONCIERGE.equals(ctx.caller()) || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return false;
        }
        Optional<GuestVisit> visit = visits.findByReservation(order.id());
        if (visit.isEmpty() || visit.get().id() == null) {
            return false;
        }
        return turns.sessionOf(visit.get().id()).filter(ctx.sessionId()::equals).isPresent();
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
