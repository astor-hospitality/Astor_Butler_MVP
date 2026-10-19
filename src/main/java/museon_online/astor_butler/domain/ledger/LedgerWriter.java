package museon_online.astor_butler.domain.ledger;

import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The only way into the visit ledger. With the flag off it touches nothing; phones are masked and turns without the
 * guest's consent lose their text before writing; a failure is logged and never reaches the guest, the booking or the
 * scenario. Each written turn publishes {@link VisitTurnAppended}. A Concierge turn written without a visit goes into
 * the visit its session is already linked to (or its booking's visit), so a session's turns never fall out of it.
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
        VisitTurnDraft turn = draft.withText(draft.hidden() ? null : PhoneMask.mask(draft.text()));
        if (turn.visitId() == null && turn.sessionId() != null && !turn.sessionId().isBlank()) {
            // A session already linked to a visit writes into it: its turns never fall out of the visit again.
            turn = turn.withVisit(sessionVisit(turn.sessionId()));
        }
        long id;
        try {
            id = turns.append(turn);
        } catch (RuntimeException e) {
            log.warn("Visit turn not written: visit={}, side={}, kind={}, reason={}", turn.visitId(), turn.side(), turn.kind(),
                    e.getClass().getSimpleName());
            return OptionalLong.empty();
        }
        announce(turn.visitId());
        return OptionalLong.of(id);
    }

    /**
     * The visit of a session's own booking whose visit was opened after the booking (so nothing linked the session to
     * it then); the session's earlier turns are moved into it on the way. Empty while there is none or on failure.
     */
    public Optional<Long> visitBookedBy(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            for (Long reservationId : turns.reservationsBookedBy(sessionId)) {
                Optional<GuestVisit> visit = visits.findByReservation(reservationId);
                if (visit.isPresent() && visit.get().id() != null) {
                    long visitId = visit.get().id();
                    linkSession(sessionId, visitId);
                    return Optional.of(visitId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Visit of a session's booking not found: reason={}", e.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    /** The visit the session is linked to, else the visit of its booking; null when neither is known. */
    private Long sessionVisit(String sessionId) {
        try {
            Optional<Long> linked = turns.visitOfSession(sessionId);
            if (linked != null && linked.isPresent()) {
                return linked.get();
            }
        } catch (RuntimeException e) {
            log.warn("Visit of a session not read for the ledger: reason={}", e.getClass().getSimpleName());
            return null;
        }
        return visitBookedBy(sessionId).orElse(null);
    }

    /** A chat message, written into the visit whose window holds now (or with no visit when there is none). */
    public void message(Long chatId, TurnSide side, TurnChannel channel, String text) {
        message(chatId, side, channel, text, null);
    }

    /** The same with the speaker's goal (the Butler's agent speaks for the venue: {@code VENUE}). */
    public void message(Long chatId, TurnSide side, TurnChannel channel, String text, String goal) {
        if (!properties.isEnabled()) {
            return;
        }
        write(new VisitTurnDraft(activeVisit(chatId), null, side, channel, TurnKind.MESSAGE, text, null, goal));
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
