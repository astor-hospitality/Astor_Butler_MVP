package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.JourneyStage;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Shared shapes of the commitment tool tests. */
public final class CapabilityFixtures {

    public static final long BUTLER_CHAT = 501L;
    public static final String SESSION = "c-1";
    /** 19:00 in Yekaterinburg on 20.10.2026. */
    public static final Instant START = Instant.parse("2026-10-20T14:00:00Z");
    /** The table of {@link ReservationCreateToolTest#args()}: 20.10.2026, 19:00, two guests. */
    public static final GuestAgreement.Table TABLE = new GuestAgreement.Table(LocalDate.of(2026, 10, 20), LocalTime.of(19, 0), 2);
    /** The guest said «да» to {@link #TABLE}, which the venue offered in its previous answer. */
    public static final GuestAgreement AGREED = GuestAgreement.of("да", List.of(TABLE));

    private CapabilityFixtures() {
    }

    /** A Concierge turn in which the guest has agreed to {@link #TABLE}. */
    public static CapabilityContext concierge(String key) {
        return concierge(key, AGREED);
    }

    public static CapabilityContext concierge(String key, GuestAgreement agreement) {
        return new CapabilityContext(CapabilityContext.CONCIERGE, 9001L, SESSION, null, key, "Анна", "+79120000060", agreement);
    }

    /** A turn of the Butler's own bot in which the guest has agreed to {@link #TABLE}. */
    public static CapabilityContext butler(String key) {
        return butler(key, AGREED);
    }

    public static CapabilityContext butler(String key, GuestAgreement agreement) {
        return new CapabilityContext(CapabilityContext.BUTLER_AGENT, BUTLER_CHAT, null, null, key, "Анна", "+79120000060", agreement);
    }

    public static TableReservationOrder order(long id, Long chatId, TableReservationStatus status) {
        return order(id, chatId, status, START);
    }

    public static TableReservationOrder order(long id, Long chatId, TableReservationStatus status, Instant start) {
        return new TableReservationOrder(id, chatId, chatId, null, 5L, "T5", "Стол у окна", null, null, status,
                chatId == null ? "CONCIERGE" : "TELEGRAM", start, start.plus(Duration.ofMinutes(90)), 2, "Анна",
                "+79120000060", null, null, null, null, null, null, null);
    }

    public static GuestVisit visit(long id, long reservationId, Long chatId, JourneyStage stage) {
        return new GuestVisit(id, reservationId, chatId, "+79120000060", "AERIS", stage, null, null, 0, START,
                START.minus(Duration.ofDays(1)), null);
    }
}
