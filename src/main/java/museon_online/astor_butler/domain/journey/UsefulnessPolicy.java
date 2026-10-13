package museon_online.astor_butler.domain.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The usefulness filter (spec §3.2): a bot-first message goes out only from the whitelist, each kind once per visit
 * (STAFF_COMING once per request), never to a guest who opted out. A failure of the filter means silence.
 */
@Component
public class UsefulnessPolicy {

    private static final Logger log = LoggerFactory.getLogger(UsefulnessPolicy.class);

    private final GuestVisitRepository visits;
    private final UsefulnessPolicyProperties properties;

    public UsefulnessPolicy(GuestVisitRepository visits, UsefulnessPolicyProperties properties) {
        this.visits = visits;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public ProactiveDecision allow(Long reservationId, Long chatId, ProactiveMessage kind) {
        if (!properties.isEnabled()) {
            return ProactiveDecision.allow();
        }
        try {
            if (chatId != null && visits.optedOut(chatId)) {
                return new ProactiveDecision.Silent("opted out");
            }
            Optional<GuestVisit> visit = reservationId == null ? Optional.empty() : visits.findByReservation(reservationId);
            if (visit.isEmpty() || kind == ProactiveMessage.STAFF_COMING) {
                return ProactiveDecision.allow();
            }
            if (visits.wasSent(visit.get().id(), kind.name())) {
                return new ProactiveDecision.Silent("already sent");
            }
            visits.markSent(visit.get().id(), kind.name());
            return ProactiveDecision.allow();
        } catch (RuntimeException e) {
            log.warn("Usefulness filter failed, the message stays unsent: reservation={}, kind={}, reason={}", reservationId, kind, e.toString());
            return new ProactiveDecision.Silent("error");
        }
    }

    /** Convenience for senders: true when the message may go out. */
    public boolean permits(Long reservationId, Long chatId, ProactiveMessage kind) {
        return allow(reservationId, chatId, kind) instanceof ProactiveDecision.Allow;
    }
}
