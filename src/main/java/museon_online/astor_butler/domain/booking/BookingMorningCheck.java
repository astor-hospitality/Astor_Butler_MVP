package museon_online.astor_butler.domain.booking;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One morning reconfirmation of one reservation: when it was sent (or why it was not), and what the guest answered.
 * The row exists from the moment the send is attempted, which is what keeps the question from being asked twice.
 */
public record BookingMorningCheck(
        Long reservationId,
        Long chatId,
        LocalDate visitDate,
        Instant sentAt,
        String deliveryError,
        String answer,
        Instant answeredAt,
        Instant guestReconfirmedAt,
        Instant createdAt
) {
    public static final String ANSWER_KEEP = "KEEP";
    public static final String ANSWER_MOVE = "MOVE";
    public static final String ANSWER_CANCEL = "CANCEL";

    public boolean sent() {
        return sentAt != null;
    }

    public boolean reconfirmed() {
        return guestReconfirmedAt != null;
    }
}
