package museon_online.astor_butler.domain.ledger;

import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.OptionalLong;

/**
 * The only way into the visit ledger. With the flag off it touches nothing; phones are masked and turns without the
 * guest's consent lose their text before writing; a failure is logged and never reaches the guest, the booking or the
 * scenario. Each written turn publishes {@link VisitTurnAppended}.
 */
@Component
public class LedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(LedgerWriter.class);

    private final VisitTurnRepository turns;
    private final GuestVisitRepository visits;
    private final LedgerProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public LedgerWriter(VisitTurnRepository turns, GuestVisitRepository visits, LedgerProperties properties,
                        ApplicationEventPublisher events) {
        this(turns, visits, properties, events, Clock.systemUTC());
    }

    public LedgerWriter(VisitTurnRepository turns, GuestVisitRepository visits, LedgerProperties properties,
                        ApplicationEventPublisher events, Clock clock) {
        this.turns = turns;
        this.visits = visits;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public void write(VisitTurnDraft draft) {
        append(draft);
    }

    /** Writes the turn and returns its id; empty when the ledger is off or the write failed. */
    public OptionalLong append(VisitTurnDraft draft) {
        if (!properties.isEnabled() || draft == null) {
            return OptionalLong.empty();
        }
        long id;
        try {
            id = turns.append(draft.withText(draft.hidden() ? null : PhoneMask.mask(draft.text())));
        } catch (RuntimeException e) {
            log.warn("Visit turn not written: visit={}, side={}, kind={}, reason={}", draft.visitId(), draft.side(), draft.kind(),
                    e.getClass().getSimpleName());
            return OptionalLong.empty();
        }
        announce(draft.visitId());
        return OptionalLong.of(id);
    }

    /** A chat message, written into the visit whose window holds now (or with no visit when there is none). */
    public void message(Long chatId, TurnSide side, TurnChannel channel, String text) {
        if (!properties.isEnabled()) {
            return;
        }
        write(new VisitTurnDraft(activeVisit(chatId), null, side, channel, TurnKind.MESSAGE, text, null, null));
    }

    /** Moves the Concierge session's earlier turns into the visit; 0 when off or on failure. */
    public int linkSession(String sessionId, long visitId) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        int linked;
        try {
            linked = turns.linkSession(sessionId, visitId);
        } catch (RuntimeException e) {
            log.warn("Visit session not linked: visit={}, reason={}", visitId, e.getClass().getSimpleName());
            return 0;
        }
        if (linked > 0) {
            announce(visitId);
        }
        return linked;
    }

    /** The row is already committed: a failing listener is logged and never turns the write into a failure. */
    private void announce(Long visitId) {
        try {
            events.publishEvent(new VisitTurnAppended(visitId));
        } catch (RuntimeException e) {
            log.warn("Visit turn listener failed: visit={}, reason={}", visitId, e.getClass().getSimpleName());
        }
    }

    private Long activeVisit(Long chatId) {
        if (chatId == null) {
            return null;
        }
        try {
            return visits.activeByChat(chatId, clock.instant()).map(GuestVisit::id).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Active visit not found for the ledger: reason={}", e.getClass().getSimpleName());
            return null;
        }
    }
}
