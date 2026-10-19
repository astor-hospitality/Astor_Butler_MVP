package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationOrder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Puts a guest's table request in front of the waiter of the table and says so to the guest. The waiter is the day's
 * assignment ({@code /официант}) or, when nobody has the table, the waiter on shift whose standing zone holds it
 * (picked once and kept for the day); nobody fits — the team, with the zone named on the card.
 */
public class TableRequestRouter {

    public static final String NOT_DELIVERED = "Не получилось передать команде — позовите, пожалуйста, официанта жестом.";
    static final String TO_THE_TEAM = "Передал команде, скоро подойдут.";

    /** The guest's reservation by id. */
    public interface Reservations {
        Optional<TableReservationOrder> find(long reservationId);
    }

    /** The waiter of the table for the day. */
    public interface Waiters {

        /** The day's assignment only, nothing picked: what the seated hint, the tips and the return loop say. */
        Optional<String> nameOf(String tableCode, LocalDate shiftDate);

        /** Whom a request goes to: the assignment, or a waiter picked (and kept) by zone and shift, or the team. */
        default Addressee forRequest(String tableCode, LocalDate shiftDate) {
            return new Addressee(nameOf(tableCode, shiftDate).orElse(null), null);
        }
    }

    /**
     * Whom the card is for.
     *
     * @param waiterName the table's waiter; null — the team
     * @param zone       the table's zone, for «нет официанта в зоне …» when nobody is there; null when unknown
     */
    public record Addressee(String waiterName, String zone) {

        public static Addressee team(String zone) {
            return new Addressee(null, zone);
        }

        public boolean known() {
            return waiterName != null && !waiterName.isBlank();
        }
    }

    /** The card to the staff chat. */
    public interface StaffChat {
        void send(TableReservationOrder order, TableRequest request, String guestText, Addressee to);
    }

    /** What the guest is told and whom the card went to. */
    public record Routed(String answer, boolean delivered, String waiterName) {
    }

    private final Reservations reservations;
    private final Waiters waiters;
    private final StaffChat staffChat;
    private final Clock clock;
    private final ZoneId zone;

    public TableRequestRouter(Reservations reservations, Waiters waiters, StaffChat staffChat, Clock clock, String zoneId) {
        this.reservations = reservations;
        this.waiters = waiters;
        this.staffChat = staffChat;
        this.clock = clock;
        this.zone = ZoneId.of(zoneId);
    }

    public String route(long reservationId, TableRequest request, String guestText) {
        return deliver(reservationId, request, guestText).answer();
    }

    public Routed deliver(long reservationId, TableRequest request, String guestText) {
        Optional<TableReservationOrder> order = reservations.find(reservationId);
        if (order.isEmpty()) {
            return new Routed(NOT_DELIVERED, false, null);
        }
        Addressee to = addressee(order.get());
        staffChat.send(order.get(), request, guestText, to);
        return to.known()
                ? new Routed("Передал. " + to.waiterName() + " скоро подойдёт.", true, to.waiterName())
                : new Routed(TO_THE_TEAM, true, null);
    }

    public Optional<String> waiterName(TableReservationOrder order) {
        if (order == null || order.tableCode() == null) {
            return Optional.empty();
        }
        return waiters.nameOf(order.tableCode(), today());
    }

    private Addressee addressee(TableReservationOrder order) {
        if (order.tableCode() == null || order.tableCode().isBlank()) {
            return Addressee.team(null);
        }
        Addressee to = waiters.forRequest(order.tableCode(), today());
        return to == null ? Addressee.team(null) : to;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
