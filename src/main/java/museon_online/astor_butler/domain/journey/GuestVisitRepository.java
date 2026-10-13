package museon_online.astor_butler.domain.journey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** The guest journey ledger: visits, their events, bot-first messages already sent, and the guest's opt-out. */
@Repository
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
     * venue time (spec §3.1); a visit without a day keeps its window for two days from opening.
     */
    public Optional<GuestVisit> activeByChat(long chatId, Instant now) {
        List<GuestVisit> recent = jdbc.query("SELECT " + VISIT_COLUMNS
                + " FROM guest_visits WHERE chat_id = ? AND opened_at <= ? ORDER BY opened_at DESC LIMIT 5",
                VISIT, chatId, Timestamp.from(now));
        return recent.stream().filter(visit -> now.isBefore(windowEnd(visit))).findFirst();
    }

    Instant windowEnd(GuestVisit visit) {
        if (visit.visitDay() == null) {
            return visit.openedAt().plus(2, ChronoUnit.DAYS);
        }
        return visit.visitDay().atZone(zone).toLocalDate().plusDays(2).atStartOfDay(zone).toInstant();
    }

    public GuestVisit open(long reservationId, Long chatId, String guestPhone, String venueCode, Instant visitDay) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO guest_visits(reservation_id, chat_id, guest_phone, venue_code, stage, visit_day) VALUES (?, ?, ?, ?, ?, ?)",
                        new String[]{"id"});
                statement.setLong(1, reservationId);
                statement.setObject(2, chatId);
                statement.setString(3, guestPhone);
                statement.setString(4, venueCode);
                statement.setString(5, JourneyStage.REQUESTED.name());
                statement.setTimestamp(6, visitDay == null ? null : Timestamp.from(visitDay));
                return statement;
            }, keys);
        } catch (DuplicateKeyException alreadyOpen) {
            return findByReservation(reservationId).orElseThrow();
        }
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

    public int incrementGuestMessages(long visitId) {
        jdbc.update("UPDATE guest_visits SET guest_messages = guest_messages + 1 WHERE id = ?", visitId);
        Integer count = jdbc.queryForObject("SELECT guest_messages FROM guest_visits WHERE id = ?", Integer.class, visitId);
        return count == null ? 0 : count;
    }

    public List<GuestVisitEvent> unmirrored(int limit) {
        return jdbc.query("SELECT id, visit_id, kind, axis, source, note, occurred_at, mirrored_at FROM guest_visit_events "
                + "WHERE mirrored_at IS NULL ORDER BY occurred_at, id LIMIT ?", EVENT, limit);
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
