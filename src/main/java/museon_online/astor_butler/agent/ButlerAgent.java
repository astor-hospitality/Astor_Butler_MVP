package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.PhoneMask;
import museon_online.astor_butler.domain.ledger.TurnChannel;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The restaurant's AI agent. It negotiates with the Concierge for the venue ({@link #negotiate}) and, for allowlisted
 * chats, answers guests in the Butler's own bot ({@link #reply}). Each turn: the system prompt with the venue's goals,
 * accepted lessons and what is known of the guest, the conversation so far, then {@link AgentLoop} with the
 * Capability tools. Any failure is a fallback: the Concierge gets {@code fallback=true}, the bot keeps its scenarios.
 *
 * <p>On the negotiation path the tools run on behalf of the Concierge session (caller {@code CONCIERGE}): its booking
 * links the session to the new visit, and only that session reaches it later. The Butler writes the Concierge's
 * message and its own reply to the ledger; the guest's own words are the Concierge's to record.
 *
 * <p>Each turn carries the guest's {@link GuestAgreement}: whether the guest's own words (the Concierge's
 * {@code guestText}, the bot's message) say yes, and the tables the previous answered turn put to the guest
 * ({@link #proposed}). The tools, not the model, decide with it whether a table request or a lunch order may run.
 */
@Component
public class ButlerAgent {

    static final String GOAL_VENUE = "VENUE";
    static final String GOAL_GUEST = "GUEST";
    private static final int MAX_SAID = 1_500;
    private static final int MAX_KEY_SCOPE = 60;
    /** Leaves room for {@code :<call>} under IdempotencyStore.MAX_KEY. */
    private static final int MAX_TURN_KEY = 76;
    private static final Logger log = LoggerFactory.getLogger(ButlerAgent.class);
    private static final String DEFAULT_ZONE = "Asia/Yekaterinburg";

    private final AgentLoop loop;
    private final CapabilityRegistry registry;
    private final AgentProperties properties;
    private final AgentLessonRepository lessons;
    private final GuestMemory memory;
    private final VenueFactsCatalog venue;
    private final CapabilityProperties capabilities;
    private final LedgerWriter ledger;
    private final VisitTurnRepository turns;
    private final GuestVisitRepository visits;
    private final AgentHistory history;
    private Clock clock = Clock.systemUTC();
    private ZoneId zone = ZoneId.of(DEFAULT_ZONE);

    public ButlerAgent(AgentLoop loop, CapabilityRegistry registry, AgentProperties properties, AgentLessonRepository lessons,
                       GuestMemory memory, VenueFactsCatalog venue, CapabilityProperties capabilities, LedgerWriter ledger,
                       VisitTurnRepository turns, GuestVisitRepository visits) {
        this.loop = loop;
        this.registry = registry;
        this.properties = properties;
        this.lessons = lessons;
        this.memory = memory;
        this.venue = venue;
        this.capabilities = capabilities;
        this.ledger = ledger;
        this.turns = turns;
        this.visits = visits;
        this.history = new AgentHistory(properties);
    }

    /** Warns at startup when the agent is on without the guest journey it reads visits from. */
    @Autowired(required = false)
    public void setGuestJourney(GuestJourneyProperties journey) {
        if (journey != null) {
            journey.prerequisiteWarning("ASTOR_AGENT_ENABLED", properties.isEnabled()).ifPresent(log::warn);
            try {
                zone = ZoneId.of(journey.getZoneId());
            } catch (RuntimeException e) {
                log.warn("Butler agent keeps the zone {}: reason={}", DEFAULT_ZONE, e.getClass().getSimpleName());
            }
        }
    }

    /** For tests: «today» in the prompt. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Whether the agent takes this chat of the Butler's own bot (flag on and the chat in the allowlist). */
    public boolean answers(Long chatId) {
        return properties.allows(chatId);
    }

    public NegotiationReply negotiate(NegotiationTurn turn) {
        String sessionId = turn == null || turn.sessionId() == null ? null : turn.sessionId().trim();
        if (sessionId == null || sessionId.isEmpty()) {
            return new NegotiationReply(null, null, List.of(), turn == null ? null : turn.visitId(), true);
        }
        String key = "s:" + sessionId;
        Long visitId = turn.visitId() != null ? turn.visitId() : sessionVisit(key, sessionId);
        if (!properties.isEnabled()) {
            return new NegotiationReply(null, null, List.of(), visitId, true);
        }
        history.rememberVisit(key, visitId);
        // Without the guest's consent the Concierge's words stay out of the ledger: the row only says it spoke.
        boolean consent = Boolean.TRUE.equals(turn.guestConsent());
        write(new VisitTurnDraft(visitId, sessionId, TurnSide.CONCIERGE, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE,
                consent ? blankToNull(turn.conciergeMessage()) : null, consent ? null : Map.of("hidden", true), GOAL_GUEST),
                turn.conciergeMessage() != null && !turn.conciergeMessage().isBlank());

        String said = negotiationMessage(turn);
        CapabilityContext ctx = new CapabilityContext(CapabilityContext.CONCIERGE, null, sessionId, visitId,
                turnKey(sessionId, turnOf(turn, key)), blankToNull(turn.guestName()), blankToNull(turn.guestPhone()),
                GuestAgreement.of(turn.guestText(), history.proposed(key)));
        // Without consent the model audit keeps no words of this turn either.
        AgentOutcome outcome = run(key, said, ctx, turn.guestPhone(), null, consent, properties.getTurnTimeoutMs());

        Long newVisit = visitOf(outcome);
        if (newVisit != null && !newVisit.equals(visitId)) {
            visitId = newVisit;
            history.rememberVisit(key, visitId);
            safely(() -> ledger.linkSession(sessionId, newVisit));
        }
        List<Map<String, Object>> facts = facts(outcome);
        if (outcome.fallback()) {
            // Without a turn id a Concierge retry reuses the counter; once a tool ran the next turn needs new keys.
            if (blankToNull(turn.turnId()) == null && outcome.toolCalls() > 0) {
                history.skipTurn(key);
            }
            history.withdrawProposals(key);
            write(fallbackTurn(visitId, sessionId, TurnChannel.CONCIERGE_TG, outcome), true);
            return new NegotiationReply(null, null, facts, visitId, true);
        }
        Map<String, Object> offer = offer(outcome);
        history.add(key, said, outcome.text(), digest(outcome), proposed(outcome));
        Map<String, Object> payload = new LinkedHashMap<>();
        if (offer != null) {
            payload.put("offerKind", offer.get("kind"));
        }
        write(new VisitTurnDraft(visitId, sessionId, TurnSide.BUTLER, TurnChannel.CONCIERGE_TG,
                offer == null ? TurnKind.MESSAGE : TurnKind.OFFER,
                outcome.text(), payload.isEmpty() ? null : payload, GOAL_VENUE), true);
        return new NegotiationReply(outcome.text(), offer, facts, visitId, false);
    }

    /**
     * The agent's answer in the Butler's own bot; empty when the chat is not allowlisted or on fallback (the
     * scenarios answer then). The reply itself is written to the ledger by the message gateway.
     */
    public Optional<String> reply(IncomingMessage incoming, Long visitId) {
        if (incoming == null || !answers(incoming.chatId())) {
            return Optional.empty();
        }
        Long chatId = incoming.chatId();
        String key = "c:" + chatId;
        Long visit = visitId != null ? visitId : activeVisit(chatId);
        String said = clip(PhoneMask.mask(incoming.text() == null ? "" : incoming.text().trim()));
        if (said.isEmpty()) {
            return Optional.empty();
        }
        CapabilityContext ctx = new CapabilityContext(CapabilityContext.BUTLER_AGENT, chatId, null, visit,
                turnKey("tg:" + chatId, history.nextTurn(key)), blankToNull(incoming.firstName()), blankToNull(incoming.contactPhone()),
                GuestAgreement.of(incoming.text(), history.proposed(key)));
        // The bot's single update thread waits the bot budget at most; on expiry the scenarios answer.
        AgentOutcome outcome = run(key, said, ctx, incoming.contactPhone(), chatId, true, properties.getBotTurnTimeoutMs());
        if (outcome.fallback()) {
            // The bot has no client retries: the next guest message is always a new turn with new keys.
            history.skipTurn(key);
            history.withdrawProposals(key);
            // Without a visit the row would belong to nothing on the board; the loop has logged the fallback.
            write(fallbackTurn(visit, null, TurnChannel.BUTLER_TG, outcome), visit != null);
            return Optional.empty();
        }
        history.add(key, said, outcome.text(), digest(outcome), proposed(outcome));
        return Optional.of(outcome.text());
    }

    private AgentOutcome run(String key, String said, CapabilityContext ctx, String phone, Long chatId, boolean auditContent,
                             long turnTimeoutMs) {
        try {
            List<ModelChatMessage> messages = new ArrayList<>();
            messages.add(ModelChatMessage.system(ButlerPrompt.system(
                    quietly(() -> lessons.accepted("BUTLER")),
                    quietly(() -> memory.of(blankToNull(phone), chatId)),
                    venueFacts(), LocalDate.now(clock.withZone(zone)))));
            messages.addAll(history.messages(key));
            messages.add(ModelChatMessage.user(said));
            AgentOutcome outcome = loop.run(messages, registry.asModelTools(), ctx, auditContent, turnTimeoutMs);
            return outcome == null ? AgentOutcome.fallback(List.of(), 0) : outcome;
        } catch (RuntimeException e) {
            log.warn("Butler agent turn failed: reason={}", e.getClass().getSimpleName());
            return AgentOutcome.fallback(List.of(), 0);
        }
    }

    /** The Concierge's turn id when it sent one (a retry repeats it), else the session's own turn counter. */
    private Object turnOf(NegotiationTurn turn, String key) {
        String turnId = blankToNull(turn.turnId());
        return turnId != null ? turnId : history.nextTurn(key);
    }

    /** What the venue hears this turn: the guest's position, the Concierge's words and the guest's own, phones masked. */
    static String negotiationMessage(NegotiationTurn turn) {
        List<String> lines = new ArrayList<>();
        String position = position(turn.conciergePosition());
        if (position != null) {
            lines.add("Позиция гостя: " + position);
        }
        if (turn.guestName() != null && !turn.guestName().isBlank()) {
            lines.add("Имя гостя: " + turn.guestName().trim());
        }
        if (turn.conciergeMessage() != null && !turn.conciergeMessage().isBlank()) {
            lines.add("Консьерж: " + turn.conciergeMessage().trim());
        }
        if (turn.guestText() != null && !turn.guestText().isBlank()) {
            lines.add("Гость: " + turn.guestText().trim());
        }
        return clip(PhoneMask.mask(String.join("\n", lines)));
    }

    /**
     * «2026-10-15, 13:00, 2 гостя, бюджет 1500, без лука»; null when nothing is known. After a REVIEW event the
     * Concierge adds the guest's rating ({@code review}) and what it is about ({@code pendingReview}):
     * «…; оценка гостя: 5 (комментарий); ждём оценку: бронь 41, счёт 77».
     */
    static String position(Map<String, Object> position) {
        if (position == null || position.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        text(position.get("date")).ifPresent(parts::add);
        text(position.get("time")).ifPresent(parts::add);
        text(position.get("partySize")).ifPresent(size -> parts.add(size + " " + guests(size)));
        text(position.get("budget")).ifPresent(budget -> parts.add("бюджет " + budget));
        Object constraints = position.get("constraints");
        if (constraints instanceof Collection<?> list) {
            list.forEach(item -> text(item).ifPresent(parts::add));
        } else {
            text(constraints).ifPresent(parts::add);
        }
        List<String> sections = new ArrayList<>();
        if (!parts.isEmpty()) {
            sections.add(String.join(", ", parts));
        }
        review(position.get("review")).ifPresent(sections::add);
        pendingReview(position.get("pendingReview")).ifPresent(sections::add);
        return sections.isEmpty() ? null : String.join("; ", sections);
    }

    private static Optional<String> review(Object review) {
        if (!(review instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Optional<String> stars = text(map.get("stars"));
        if (stars.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("оценка гостя: " + stars.get() + text(map.get("comment")).map(comment -> " (" + comment + ")").orElse(""));
    }

    private static Optional<String> pendingReview(Object pending) {
        if (!(pending instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Optional<String> reservation = text(map.get("reservationId"));
        if (reservation.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("ждём оценку: бронь " + reservation.get() + text(map.get("billId")).map(bill -> ", счёт " + bill).orElse(""));
    }

    private static String guests(String size) {
        try {
            long n = Long.parseLong(size.trim());
            long tens = n % 100;
            long last = n % 10;
            if (tens >= 11 && tens <= 14) {
                return "гостей";
            }
            return last == 1 ? "гость" : last >= 2 && last <= 4 ? "гостя" : "гостей";
        } catch (NumberFormatException e) {
            return "гостей";
        }
    }

    private static Optional<String> text(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = value instanceof Double number && number == Math.rint(number) ? String.valueOf(number.longValue())
                : value.toString().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** The visit a booking of this turn opened: from the tool's facts, else by its reservation. */
    private Long visitOf(AgentOutcome outcome) {
        Long found = null;
        for (AgentOutcome.ToolUse use : outcome.uses()) {
            if (!"reservation_create".equals(use.tool()) || use.result() == null || !use.result().ok()) {
                continue;
            }
            Map<String, Object> facts = use.result().facts();
            Long visitId = number(facts.get("visitId"));
            if (visitId == null) {
                Long reservationId = number(facts.get("reservationId"));
                if (reservationId != null) {
                    visitId = quietlyOptional(() -> visits.findByReservation(reservationId)).map(GuestVisit::id).orElse(null);
                }
            }
            if (visitId != null) {
                found = visitId;
            }
        }
        return found;
    }

    /** The facts of the turn's last successful availability check, booking or lunch order, with its kind. */
    static Map<String, Object> offer(AgentOutcome outcome) {
        for (int i = outcome.uses().size() - 1; i >= 0; i--) {
            AgentOutcome.ToolUse use = outcome.uses().get(i);
            String kind = switch (use.tool() == null ? "" : use.tool()) {
                case "table_availability" -> "ALTERNATIVE_TIME";
                case "reservation_create" -> "RESERVATION";
                case "lunch_order_place" -> "LUNCH_ORDER";
                default -> null;
            };
            if (kind != null && use.result() != null && use.result().ok()) {
                Map<String, Object> offer = new LinkedHashMap<>(use.result().facts());
                offer.put("kind", kind);
                return offer;
            }
        }
        return null;
    }

    /**
     * The tables this turn put to the guest: the free time and the later times of an availability check, and a table
     * request refused for want of the guest's yes (the model is to ask for it).
     */
    static List<GuestAgreement.Table> proposed(AgentOutcome outcome) {
        List<GuestAgreement.Table> tables = new ArrayList<>();
        for (AgentOutcome.ToolUse use : outcome.uses()) {
            CapabilityResult result = use.result();
            Map<String, Object> facts = result == null || result.facts() == null ? Map.of() : result.facts();
            if ("table_availability".equals(use.tool()) && result != null && result.ok()) {
                if (facts.get("free") instanceof Collection<?> free && !free.isEmpty()) {
                    GuestAgreement.Table.of(facts.get("date"), facts.get("time"), facts.get("partySize")).ifPresent(tables::add);
                }
                if (facts.get("alternatives") instanceof Collection<?> alternatives) {
                    for (Object alternative : alternatives) {
                        if (alternative instanceof Map<?, ?> later) {
                            GuestAgreement.Table.of(facts.get("date"), later.get("time"), facts.get("partySize")).ifPresent(tables::add);
                        }
                    }
                }
            } else if ("reservation_create".equals(use.tool()) && result != null && !result.ok()
                    && GuestAgreement.NEEDS_GUEST_YES.equals(facts.get("reason"))) {
                GuestAgreement.Table.of(facts.get("date"), facts.get("time"), facts.get("partySize")).ifPresent(tables::add);
            }
        }
        return tables;
    }

    private static List<Map<String, Object>> facts(AgentOutcome outcome) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (AgentOutcome.ToolUse use : outcome.uses()) {
            CapabilityResult result = use.result();
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("tool", use.tool());
            fact.put("ok", result != null && result.ok());
            fact.put("error", result == null ? CapabilityResult.UNAVAILABLE : result.error());
            fact.put("facts", result == null ? Map.of() : result.facts());
            facts.add(fact);
        }
        return facts;
    }

    /** A one-line memory of the turn for the «Ранее» line: the tools' scalar facts, or the start of what was said. */
    private static String digest(AgentOutcome outcome) {
        List<String> parts = new ArrayList<>();
        for (AgentOutcome.ToolUse use : outcome.uses()) {
            if (use.result() == null) {
                continue;
            }
            List<String> values = new ArrayList<>();
            use.result().facts().forEach((name, value) -> {
                if (values.size() < 4 && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    values.add(name + "=" + PhoneMask.mask(value.toString()));
                }
            });
            parts.add(use.tool() + (use.result().ok() ? "" : " " + use.result().error())
                    + (values.isEmpty() ? "" : " " + String.join(", ", values)));
        }
        return parts.isEmpty() ? null : AgentHistory.shorten(String.join("; ", parts));
    }

    private Long sessionVisit(String key, String sessionId) {
        Long remembered = history.visit(key);
        if (remembered != null) {
            return remembered;
        }
        return quietlyOptional(() -> turns.visitOfSession(sessionId)).orElse(null);
    }

    private Long activeVisit(long chatId) {
        return quietlyOptional(() -> visits.activeByChat(chatId, Instant.now())).map(GuestVisit::id).orElse(null);
    }

    private String venueFacts() {
        List<String> lines = new ArrayList<>();
        lines.add(capabilities.getVenueName() + ", " + capabilities.getVenueAddress() + ".");
        for (VenueFactsCatalog.Topic topic : List.of(VenueFactsCatalog.Topic.PARKING, VenueFactsCatalog.Topic.KIDS,
                VenueFactsCatalog.Topic.WINE)) {
            venue.fact(topic).ifPresent(lines::add);
        }
        return String.join("\n", lines);
    }

    private static VisitTurnDraft fallbackTurn(Long visitId, String sessionId, TurnChannel channel, AgentOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "agent_fallback");
        payload.put("toolCalls", outcome.toolCalls());
        return new VisitTurnDraft(visitId, sessionId, TurnSide.BUTLER, channel, TurnKind.FALLBACK, null, payload, GOAL_VENUE);
    }

    private void write(VisitTurnDraft draft, boolean wanted) {
        if (wanted) {
            safely(() -> ledger.write(draft));
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Butler agent ledger write skipped: reason={}", e.getClass().getSimpleName());
        }
    }

    private static List<String> quietly(Supplier<List<String>> source) {
        try {
            List<String> lines = source.get();
            return lines == null ? List.of() : lines;
        } catch (RuntimeException e) {
            log.warn("Butler agent context skipped: reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static <T> Optional<T> quietlyOptional(Supplier<Optional<T>> source) {
        try {
            Optional<T> value = source.get();
            return value == null ? Optional.empty() : value;
        } catch (RuntimeException e) {
            log.warn("Butler agent lookup skipped: reason={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@code <scope>:<turn>}; the loop adds {@code :<call>}. A part too long for the idempotency store (80 characters
     * with the suffixes) is replaced by its SHA-256 prefix: a long scope alone, or the whole key when a long turn id
     * still makes it too long.
     */
    static String turnKey(String scope, Object turn) {
        String prefix = scope.length() > MAX_KEY_SCOPE ? hashed(scope) : scope;
        String key = prefix + ":" + turn;
        return key.length() > MAX_TURN_KEY ? hashed(scope + ":" + turn) : key;
    }

    private static String hashed(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "h" + HexFormat.of().formatHex(hash).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            return value.substring(0, MAX_KEY_SCOPE);
        }
    }

    private static String clip(String text) {
        return text.length() <= MAX_SAID ? text : text.substring(0, MAX_SAID);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
