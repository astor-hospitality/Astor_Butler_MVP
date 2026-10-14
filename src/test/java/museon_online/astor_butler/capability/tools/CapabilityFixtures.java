package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.JourneyStage;

import java.time.Duration;
import java.time.Instant;

/** Shared shapes of the commitment tool tests. */
public final class CapabilityFixtures {

    public static final long BUTLER_CHAT = 501L;
    public static final String SESSION = "c-1";
    /** 19:00 in Yekaterinburg on 20.10.2026. */
    public static final Instant START = Instant.parse("2026-10-20T14:00:00Z");

    private CapabilityFixtures() {
    }

    public static CapabilityContext concierge(String key) {
        return new CapabilityContext(CapabilityContext.CONCIERGE, 9001L, SESSION, null, key, "Анна", "+79120000060");
    }

    public static CapabilityContext butler(String key) {
        return new CapabilityContext(CapabilityContext.BUTLER_AGENT, BUTLER_CHAT, null, null, key, "Анна", "+79120000060");
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
