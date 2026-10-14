package museon_online.astor_butler.domain.journey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The guest journey ledger: visits, their events, bot-first messages already sent, and the guest's opt-out.
 * Every call runs in its own transaction: the ledger is written from inside reservation transactions, and on PostgreSQL
 * one failed ledger statement would otherwise abort — and roll back — the reservation itself.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class GuestVisitRepository {

    private static final String VISIT_COLUMNS = "id, reservation_id, chat_id, guest_phone, venue_code, stage, saby_lead_id, "
            + "returned_visit_id, guest_messages, visit_day, opened_at, closed_at";

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public GuestVisitRepository(JdbcTemplate jdbc) {
        this(jdbc, ZoneId.of("Asia/Yekaterinburg"));
    }

    @Autowired
    public GuestVisitRepository(JdbcTemplate jdbc, GuestJourneyProperties properties) {
        this(jdbc, ZoneId.of(properties.getZoneId()));
    }

    private GuestVisitRepository(JdbcTemplate jdbc, ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    public Optional<GuestVisit> findByReservation(long reservationId) {
        return jdbc.query("SELECT " + VISIT_COLUMNS + " FROM guest_visits WHERE reservation_id = ?", VISIT, reservationId)
                .stream().findFirst();
    }

    public Optional<GuestVisit> findById(long visitId) {
        return jdbc.query("SELECT " + VISIT_COLUMNS + " FROM guest_visits WHERE id = ?", VISIT, visitId).stream().findFirst();
    }

    /**
     * The visit whose window holds {@code now}: from its opening to the start of the second day after the visit day,
     * venue time (spec §3.1); a visit without a day keeps its window for two days from opening. When several windows
     * hold now, today's visit wins.
     */
    public Optional<GuestVisit> activeByChat(long chatId, Instant now) {
        List<GuestVisit> recent = jdbc.query("SELECT " + VISIT_COLUMNS
                + " FROM guest_visits WHERE chat_id = ? AND opened_at <= ? ORDER BY opened_at DESC LIMIT 5",
                VISIT, chatId, Timestamp.from(now));
        java.time.LocalDate today = now.atZone(zone).toLocalDate();
        // Today's visit first, then the one at the table, then the nearest day: a booking made from the table for next
        // week must not take over the lunch going on now.
        return recent.stream().filter(visit -> now.isBefore(windowEnd(visit))).min(java.util.Comparator
                .comparing((GuestVisit visit) -> visit.visitDay() == null || !visit.visitDay().atZone(zone).toLocalDate().equals(today))
                .thenComparing(visit -> visit.stage() != JourneyStage.SEATED && visit.stage() != JourneyStage.BILL)
                .thenComparing(visit -> visit.visitDay() == null ? Long.MAX_VALUE : Math.abs(visit.visitDay().getEpochSecond() - now.getEpochSecond())));
    }

    Instant windowEnd(GuestVisit visit) {
        if (visit.visitDay() == null) {
            return visit.openedAt().plus(2, ChronoUnit.DAYS);
        }
        return visit.visitDay().atZone(zone).toLocalDate().plusDays(2).atStartOfDay(zone).toInstant();
    }

    public GuestVisit open(long reservationId, Long chatId, String guestPhone, String venueCode, Instant visitDay) {
        // Not a caught duplicate: on PostgreSQL a failed insert aborts this transaction. A concurrent open of the same
        // reservation still fails on the unique key; the recorder reads the winner in a fresh transaction.
        jdbc.update("INSERT INTO guest_visits(reservation_id, chat_id, guest_phone, venue_code, stage, visit_day) "
                        + "SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM guest_visits WHERE reservation_id = ?)",
                reservationId, chatId, guestPhone, venueCode, JourneyStage.REQUESTED.name(),
                visitDay == null ? null : Timestamp.from(visitDay), reservationId);
        return findByReservation(reservationId).orElseThrow();
    }

    /** Writes the event; a one-shot kind already in the visit is not written again and returns false. */
    public boolean insertEvent(long visitId, VisitEventKind kind, String source, String note) {
        String clipped = note == null ? null : note.length() > 500 ? note.substring(0, 500) : note;
        if (kind.oneShot()) {
            return jdbc.update("INSERT INTO guest_visit_events(visit_id, kind, axis, source, note) SELECT ?, ?, ?, ?, ? "
                            + "WHERE NOT EXISTS (SELECT 1 FROM guest_visit_events WHERE visit_id = ? AND kind = ?)",
                    visitId, kind.name(), kind.axis().name(), source, clipped, visitId, kind.name()) > 0;
        }
        return jdbc.update("INSERT INTO guest_visit_events(visit_id, kind, axis, source, note) VALUES (?, ?, ?, ?, ?)",
                visitId, kind.name(), kind.axis().name(), source, clipped) > 0;
    }

    public void moveStage(long visitId, JourneyStage stage) {
        jdbc.update("UPDATE guest_visits SET stage = ?, closed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE closed_at END WHERE id = ?",
                stage.name(), stage.isExit() || stage == JourneyStage.AFTER || stage == JourneyStage.RETURNED, visitId);
    }

    public void updateVisitDay(long visitId, Instant visitDay) {
        jdbc.update("UPDATE guest_visits SET visit_day = ? WHERE id = ?", Timestamp.from(visitDay), visitId);
    }

    public int incrementGuestMessages(long visitId) {
        jdbc.update("UPDATE guest_visits SET guest_messages = guest_messages + 1 WHERE id = ?", visitId);
        Integer count = jdbc.queryForObject("SELECT guest_messages FROM guest_visits WHERE id = ?", Integer.class, visitId);
        return count == null ? 0 : count;
    }

    public List<GuestVisitEvent> unmirrored(int limit) {
        return jdbc.query("SELECT id, visit_id, kind, axis, source, note, occurred_at, mirrored_at FROM guest_visit_events "
                + "WHERE mirrored_at IS NULL AND mirror_attempts < ? ORDER BY occurred_at, id LIMIT ?", EVENT, MIRROR_ATTEMPTS, limit);
    }

    /** After this many failed sends an event is left out of the mirror (dead letter) and its visit's later events go on. */
    public static final int MIRROR_ATTEMPTS = 5;

    public void markMirrorFailed(long eventId) {
        jdbc.update("UPDATE guest_visit_events SET mirror_attempts = mirror_attempts + 1 WHERE id = ?", eventId);
    }

    /** The funnel stage the visit's Saby deal was last moved to. */
    public Optional<JourneyStage> sabyStage(long visitId) {
        return jdbc.queryForList("SELECT saby_stage FROM guest_visits WHERE id = ?", String.class, visitId).stream()
                .filter(java.util.Objects::nonNull).findFirst().map(JourneyStage::valueOf);
    }

    public void setSabyStage(long visitId, JourneyStage stage) {
        jdbc.update("UPDATE guest_visits SET saby_stage = ? WHERE id = ?", stage.name(), visitId);
    }

    public void markMirrored(long eventId) {
        jdbc.update("UPDATE guest_visit_events SET mirrored_at = CURRENT_TIMESTAMP WHERE id = ?", eventId);
    }

    public void setSabyLead(long visitId, String leadId) {
        jdbc.update("UPDATE guest_visits SET saby_lead_id = ? WHERE id = ?", leadId, visitId);
    }

    /** The guest's latest earlier visit with this phone opened since {@code since}; empty for a guest without a phone. */
    public Optional<GuestVisit> lastClosedByPhone(String phone, Instant since) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + VISIT_COLUMNS + " FROM guest_visits WHERE guest_phone = ? AND opened_at >= ? "
                        + "AND stage NOT IN ('CANCELLED', 'NO_SHOW') ORDER BY opened_at DESC LIMIT 1",
                VISIT, phone, Timestamp.from(since)).stream().findFirst();
    }

    public void linkReturn(long fromVisitId, long toVisitId) {
        jdbc.update("UPDATE guest_visits SET returned_visit_id = ? WHERE id = ?", toVisitId, fromVisitId);
    }

    /** The guest's earlier visit that really happened (seated or later) since {@code since}, other than this reservation. */
    public Optional<GuestVisit> previousVisitByPhone(String phone, Instant since, long excludeReservationId) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + VISIT_COLUMNS + " FROM guest_visits WHERE guest_phone = ? AND reservation_id <> ? "
                        + "AND COALESCE(visit_day, opened_at) >= ? AND stage IN ('SEATED', 'BILL', 'AFTER') AND returned_visit_id IS NULL "
                        + "ORDER BY COALESCE(visit_day, opened_at) DESC LIMIT 1",
                VISIT, phone, excludeReservationId, Timestamp.from(since)).stream().findFirst();
    }

    /** Visits in one of the stages whose day is before {@code before}, oldest first. */
    public List<GuestVisit> inStagesWithDayBefore(List<JourneyStage> stages, Instant before, int limit) {
        if (stages.isEmpty()) {
            return List.of();
        }
        String in = String.join(", ", stages.stream().map(stage -> "'" + stage.name() + "'").toList());
        return jdbc.query("SELECT " + VISIT_COLUMNS + " FROM guest_visits WHERE stage IN (" + in + ") AND visit_day < ? "
                + "ORDER BY visit_day LIMIT ?", VISIT, Timestamp.from(before), limit);
    }

    /**
     * Visits a week past whose invitation is still due: day inside [from, before), a chat to write to, not invited and
     * not already returned. Oldest first; done visits leave the list, so it never clogs.
     */
    public List<GuestVisit> awaitingInvite(Instant from, Instant before, int limit) {
        return jdbc.query("SELECT " + VISIT_COLUMNS + " FROM guest_visits v WHERE stage = 'AFTER' AND visit_day >= ? AND visit_day < ? "
                + "AND chat_id IS NOT NULL AND returned_visit_id IS NULL AND NOT EXISTS (SELECT 1 FROM guest_visit_events e "
                + "WHERE e.visit_id = v.id AND e.kind = 'INVITED') ORDER BY visit_day LIMIT ?",
                VISIT, Timestamp.from(from), Timestamp.from(before), limit);
    }

    /** The note of the visit's latest event of this kind, e.g. the rating of a review. */
    public Optional<String> eventNote(long visitId, VisitEventKind kind) {
        return jdbc.queryForList("SELECT note FROM guest_visit_events WHERE visit_id = ? AND kind = ? ORDER BY id DESC LIMIT 1",
                String.class, visitId, kind.name()).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public boolean hasEvent(long visitId, VisitEventKind kind) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM guest_visit_events WHERE visit_id = ? AND kind = ?",
                Integer.class, visitId, kind.name());
        return count != null && count > 0;
    }

    /** Whether the same guest (by chat or phone) opened another visit after this one. */
    public boolean hasLaterVisit(Long chatId, String phone, Instant openedAfter, long excludeVisitId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM guest_visits WHERE id <> ? AND opened_at > ? "
                        + "AND ((chat_id IS NOT NULL AND chat_id = ?) OR (guest_phone IS NOT NULL AND guest_phone = ?))",
                Integer.class, excludeVisitId, Timestamp.from(openedAfter), chatId, phone);
        return count != null && count > 0;
    }

    public List<GuestVisit> openedBetween(Instant from, Instant to) {
        return jdbc.query("SELECT " + VISIT_COLUMNS + " FROM guest_visits WHERE opened_at >= ? AND opened_at < ?",
                VISIT, Timestamp.from(from), Timestamp.from(to));
    }

    /** Events of one kind in the visits opened in the window. */
    public List<GuestVisitEvent> eventsOfVisitsOpenedBetween(VisitEventKind kind, Instant from, Instant to) {
        return jdbc.query("SELECT e.id, e.visit_id, e.kind, e.axis, e.source, e.note, e.occurred_at, e.mirrored_at "
                        + "FROM guest_visit_events e JOIN guest_visits v ON v.id = e.visit_id "
                        + "WHERE e.kind = ? AND v.opened_at >= ? AND v.opened_at < ?",
                EVENT, kind.name(), Timestamp.from(from), Timestamp.from(to));
    }

    public boolean wasSent(long visitId, String proactiveKind) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM guest_visit_sent WHERE visit_id = ? AND kind = ?",
                Integer.class, visitId, proactiveKind);
        return count != null && count > 0;
    }

    public void markSent(long visitId, String proactiveKind) {
        jdbc.update("INSERT INTO guest_visit_sent(visit_id, kind) SELECT ?, ? WHERE NOT EXISTS "
                + "(SELECT 1 FROM guest_visit_sent WHERE visit_id = ? AND kind = ?)", visitId, proactiveKind, visitId, proactiveKind);
    }

    public boolean optedOut(long chatId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM guest_contact_prefs WHERE chat_id = ? AND opted_out_at IS NOT NULL",
                Integer.class, chatId);
        return count != null && count > 0;
    }

    public void optOut(long chatId) {
        if (jdbc.update("UPDATE guest_contact_prefs SET opted_out_at = CURRENT_TIMESTAMP WHERE chat_id = ?", chatId) == 0) {
            jdbc.update("INSERT INTO guest_contact_prefs(chat_id, opted_out_at) VALUES (?, CURRENT_TIMESTAMP)", chatId);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static final RowMapper<GuestVisit> VISIT = (rs, row) -> new GuestVisit(
            rs.getLong("id"),
            rs.getLong("reservation_id"),
            nullableLong(rs, "chat_id"),
            rs.getString("guest_phone"),
            rs.getString("venue_code"),
            JourneyStage.valueOf(rs.getString("stage")),
            rs.getString("saby_lead_id"),
            nullableLong(rs, "returned_visit_id"),
            rs.getInt("guest_messages"),
            instant(rs, "visit_day"),
            instant(rs, "opened_at"),
            instant(rs, "closed_at"));

    private static final RowMapper<GuestVisitEvent> EVENT = (rs, row) -> new GuestVisitEvent(
            rs.getLong("id"),
            rs.getLong("visit_id"),
            VisitEventKind.valueOf(rs.getString("kind")),
            JourneyAxis.valueOf(rs.getString("axis")),
            rs.getString("source"),
            rs.getString("note"),
            instant(rs, "occurred_at"),
            instant(rs, "mirrored_at"));

}
