package museon_online.astor_butler.domain.booking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Off by default: the hostess card carries «Да» / «Нет» (and «Счёт») exactly as before. On, the card gets
 * «✏️ Изменить», which opens inline steps in the staff chat — table, time, party size — applied at once with the
 * same checks as a guest's change ({@link TableReservationService#changeByStaff}).
 */
@Component
@ConfigurationProperties(prefix = "astor.booking.staff-edit")
@Getter
@Setter
public class BookingStaffEditProperties {

    /** Show «✏️ Изменить» on the hostess card and accept its steps. */
    private boolean enabled = false;

    /** Minutes an opened edit panel stays alive without a press; after that its buttons answer «истекла». */
    private int sessionTtlMinutes = 10;

    /** Step of the time slots offered under «Время». */
    private int slotStepMinutes = 30;

    /** Largest party the number keyboard under «Гостей» offers; «+1» stops there too. */
    private int maxParty = 12;

    /** At most this many time slots on the keyboard, around the booking's current time. */
    private int maxSlots = 32;

    public Duration sessionTtl() {
        return Duration.ofMinutes(Math.max(1, sessionTtlMinutes));
    }

    public Duration slotStep() {
        return Duration.ofMinutes(Math.max(5, slotStepMinutes));
    }
}
