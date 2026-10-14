package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.journey.ProactiveMessage;
import museon_online.astor_butler.domain.journey.UsefulnessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The useful message at seating (spec 2026-10-13 §4, stage 4): when the first dish usually comes and who the waiter is. */
public class SeatedHint {

    private static final Logger log = LoggerFactory.getLogger(SeatedHint.class);

    /** A plain text to the guest of the reservation. */
    public interface GuestText {
        void send(TableReservationOrder order, String text);
    }

    private final ButlerTalkProperties properties;
    private final UsefulnessPolicy policy;
    private final TableRequestRouter router;
    private final GuestText guest;

    public SeatedHint(ButlerTalkProperties properties, UsefulnessPolicy policy, TableRequestRouter router, GuestText guest) {
        this.properties = properties;
        this.policy = policy;
        this.router = router;
        this.guest = guest;
    }

    public void send(TableReservationOrder seated) {
        if (!properties.isEnabled() || seated == null || seated.chatId() == null) {
            return;
        }
        try {
            if (!policy.permits(seated.id(), seated.chatId(), ProactiveMessage.SEATED_HINT)) {
                return;
            }
            String first = "Приятного обеда! Первое блюдо обычно подают через " + properties.getFirstDishMinutes() + " минут. ";
            String rest = router.waiterName(seated)
                    .map(name -> "Ваш официант — " + name + ", если что-то понадобится, просто напишите мне.")
                    .orElse("Если что-то понадобится, просто напишите мне.");
            guest.send(seated, first + rest);
        } catch (RuntimeException e) {
            log.warn("Seated hint not sent: reservation={}, reason={}", seated.id(), e.toString());
        }
    }
}
