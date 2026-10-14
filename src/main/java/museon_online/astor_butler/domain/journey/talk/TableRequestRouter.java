package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationOrder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/** Puts a guest's table request in front of the waiter of the table (or the team) and says so to the guest. */
public class TableRequestRouter {

    public static final String NOT_DELIVERED = "Не получилось передать команде — позовите, пожалуйста, официанта жестом.";

    /** The guest's reservation by id. */
    public interface Reservations {
        Optional<TableReservationOrder> find(long reservationId);
    }

    /** The name of the waiter assigned to the table for the day, if any. */
    public interface Waiters {
        Optional<String> nameOf(String tableCode, LocalDate shiftDate);
    }

    /** The card to the staff chat. */
    public interface StaffChat {
        void send(TableReservationOrder order, TableRequest request, String guestText, String waiterName);
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
        Optional<TableReservationOrder> order = reservations.find(reservationId);
        if (order.isEmpty()) {
            return NOT_DELIVERED;
        }
        String waiter = waiterName(order.get()).orElse(null);
        staffChat.send(order.get(), request, guestText, waiter);
        return waiter == null ? "Передал команде, скоро подойдут." : "Передал. " + waiter + " скоро подойдёт.";
    }

    public Optional<String> waiterName(TableReservationOrder order) {
        if (order == null || order.tableCode() == null) {
            return Optional.empty();
        }
        return waiters.nameOf(order.tableCode(), LocalDate.now(clock.withZone(zone)));
    }
}
