package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only SQL behind the board. A visit belongs to a window by its day (its opening when it has no day). Reads only:
 * nothing here writes, and nothing returns texts or phones unmasked except the raw rows the controller masks.
 */
@Repository
public class BoardQueries {

    static final int MAX_VISITS = 500;
    private static final String VISIT_TIME = "COALESCE(v.visit_day, v.opened_at)";
    private static final String IN_WINDOW = " " + VISIT_TIME + " >= ? AND " + VISIT_TIME + " < ? ";
    private static final List<String> CONFIRMED_OR_LATER = List.of("CONFIRMED", "VISIT_DAY", "SEATED", "BILL", "AFTER", "RETURNED");
    private static final String SABY_FAILED = "(outcome <> 'OK' OR http_status < 200 OR http_status >= 300)";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    /** Synthetic Telegram ids of UX tests. */
    static final long TEST_ID_FROM = 900_000_001L;
    static final long TEST_ID_TO = 900_000_999L;
    /** Concierge sessions of UX tests. */
    static final String TEST_SESSION_PREFIX = "c-uxtest-";
    private static final String TODAY_TIME = "COALESCE(r.requested_start_at, v.visit_day, v.opened_at)";
    /** «Тест» or «Test» as a whole word of the guest name (prod autotests under real accounts); «Тестов» is a real guest. */
    private static final String TEST_NAME = testWord("ТЕСТ") + " OR " + testWord("TEST");
    /**
     * A test guest: a synthetic id on the reservation or the visit, «Тест» in the guest name, or a UX-test Concierge
     * session in the visit's ledger.
     */
    private static final String TEST_GUEST = "(" + testId("r.telegram_user_id") + " OR " + testId("r.chat_id") + " OR "
            + testId("v.chat_id") + " OR " + TEST_NAME + " OR EXISTS (SELECT 1 FROM visit_turns s WHERE s.visit_id = v.id AND s.session_id LIKE '"
            + TEST_SESSION_PREFIX + "%'))";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BoardQueries(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** One visit row with its reservation's guest name and table; phone and name are raw and masked by the caller. */
    public record VisitRow(long id, long reservationId, String stage, Instant visitDay, String guestName, String guestPhone,
                           String table, int guestMessages, Instant lastTurnAt) {
    }

    public record VisitTotals(long visits, double guestMessagesAvg, long visitsWith40Plus) {
    }

    /** One booking or lunch order of the manager's day: the visit, its reservation, its lunch bill and the guest's stars. */
    public record TodayRow(long visitId, long reservationId, String venue, String stage, Instant visitDay, Instant openedAt,
                           String reservationStatus, String source, Integer partySize, String guestName, Instant startAt,
                           Instant createdAt, String table, boolean lunch, Long lunchEstimateMinor, Long lunchVenueMinor,
                           Integer stars) {
    }

    public record ModelTotals(long calls, long errors) {
    }

    public record SabyTotals(long calls, long failures) {
    }

    public List<VisitRow> visits(Instant from, Instant to) {
        return jdbc.query("SELECT v.id, v.reservation_id, v.stage, v.visit_day, v.guest_messages, "
                        + "COALESCE(v.guest_phone, r.guest_phone) AS phone, r.guest_name, "
                        + "COALESCE(t.display_name, t.table_code) AS table_name, "
                        + "(SELECT max(vt.at) FROM visit_turns vt WHERE vt.visit_id = v.id) AS last_turn_at "
                        + "FROM guest_visits v LEFT JOIN table_reservation_orders r ON r.id = v.reservation_id "
                        + "LEFT JOIN venue_tables t ON t.id = r.table_id WHERE" + IN_WINDOW
                        + "ORDER BY " + VISIT_TIME + ", v.id LIMIT " + MAX_VISITS,
                (rs, row) -> new VisitRow(rs.getLong("id"), rs.getLong("reservation_id"), rs.getString("stage"),
                        instant(rs, "visit_day"), rs.getString("guest_name"), rs.getString("phone"), rs.getString("table_name"),
                        rs.getInt("guest_messages"), instant(rs, "last_turn_at")),
                ts(from), ts(to));
    }

    /**
     * The window's bookings and lunch orders by their start (the reservation's time, else the visit's day), oldest first;
     * test guests never come back: synthetic Telegram ids {@value #TEST_ID_FROM}–{@value #TEST_ID_TO} and Concierge
     * sessions {@code c-uxtest-*}.
     *
     * @param venue a venue code, or null for every venue
     */
    public List<TodayRow> todayRows(Instant from, Instant to, String venue) {
        List<Object> args = new ArrayList<>(List.of(ts(from), ts(to)));
        String venueFilter = "";
        if (venue != null) {
            venueFilter = "AND v.venue_code = ? ";
            args.add(venue);
        }
        return jdbc.query("SELECT v.id, v.reservation_id, v.venue_code, v.stage, v.visit_day, v.opened_at, r.status, r.source, "
                        + "r.party_size, r.guest_name, r.requested_start_at, r.created_at, "
                        + "COALESCE(t.display_name, t.table_code) AS table_name, b.id AS lunch_bill, b.estimate_minor, "
                        + "b.venue_amount_minor, (SELECT max(w.stars) FROM visit_reviews w JOIN guest_bills wb ON wb.id = w.bill_id "
                        + "WHERE wb.table_reservation_id = v.reservation_id) AS stars "
                        + "FROM guest_visits v LEFT JOIN table_reservation_orders r ON r.id = v.reservation_id "
                        + "LEFT JOIN venue_tables t ON t.id = r.table_id "
                        + "LEFT JOIN guest_bills b ON b.table_reservation_id = v.reservation_id AND b.kind = 'BUSINESS_LUNCH' "
                        + "WHERE " + TODAY_TIME + " >= ? AND " + TODAY_TIME + " < ? " + venueFilter + "AND NOT " + TEST_GUEST
                        + " ORDER BY " + TODAY_TIME + ", v.id LIMIT " + MAX_VISITS,
                (rs, row) -> new TodayRow(rs.getLong("id"), rs.getLong("reservation_id"), rs.getString("venue_code"),
                        rs.getString("stage"), instant(rs, "visit_day"), instant(rs, "opened_at"), rs.getString("status"),
                        rs.getString("source"), rs.getObject("party_size", Integer.class), rs.getString("guest_name"),
                        instant(rs, "requested_start_at"), instant(rs, "created_at"), rs.getString("table_name"),
                        rs.getObject("lunch_bill") != null, rs.getObject("estimate_minor", Long.class),
                        rs.getObject("venue_amount_minor", Long.class), rs.getObject("stars", Integer.class)),
                args.toArray());
    }

    /** Venue codes with visits since this instant, for the board's venue picker. */
    public List<String> venues(Instant since) {
        return jdbc.queryForList("SELECT DISTINCT venue_code FROM guest_visits WHERE " + VISIT_TIME.replace("v.", "")
                + " >= ? ORDER BY venue_code", String.class, ts(since));
    }

    private static String testWord(String word) {
        String name = "UPPER(COALESCE(r.guest_name, ''))";
        return "(" + name + " = '" + word + "' OR " + name + " LIKE '" + word + " %' OR " + name + " LIKE '% " + word
                + "' OR " + name + " LIKE '% " + word + " %')";
    }

    private static String testId(String column) {
        return "COALESCE(" + column + ", 0) BETWEEN " + TEST_ID_FROM + " AND " + TEST_ID_TO;
    }

    public VisitTotals visitTotals(Instant from, Instant to) {
        return jdbc.queryForObject("SELECT count(*) AS visits, avg(CAST(v.guest_messages AS DOUBLE PRECISION)) AS average, "
                        + "COALESCE(sum(CASE WHEN v.guest_messages >= 40 THEN 1 ELSE 0 END), 0) AS forty "
                        + "FROM guest_visits v WHERE" + IN_WINDOW,
                (rs, row) -> new VisitTotals(rs.getLong("visits"), rs.getDouble("average"), rs.getLong("forty")),
                ts(from), ts(to));
    }

    /** Visits with at least one Concierge or Butler turn. */
    public Set<Long> negotiatedVisits(Instant from, Instant to) {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT t.visit_id FROM visit_turns t JOIN guest_visits v ON v.id = t.visit_id "
                + "WHERE t.side IN ('CONCIERGE', 'BUTLER') AND" + IN_WINDOW, Long.class, ts(from), ts(to)));
    }

    /** Visits with a successful tool result of one of these tools ({@code payload.tool}, {@code payload.ok = true}). */
    public Set<Long> visitsWithSuccessfulTool(Instant from, Instant to, Set<String> tools) {
        Set<Long> visits = new HashSet<>();
        jdbc.query("SELECT t.visit_id, t.payload FROM visit_turns t JOIN guest_visits v ON v.id = t.visit_id "
                + "WHERE t.kind = 'TOOL_RESULT' AND t.payload IS NOT NULL AND" + IN_WINDOW, rs -> {
            Map<String, Object> payload = map(rs.getString("payload"));
            if (payload != null && Boolean.TRUE.equals(payload.get("ok")) && tools.contains(String.valueOf(payload.get("tool")))) {
                visits.add(rs.getLong("visit_id"));
            }
        }, ts(from), ts(to));
        return visits;
    }

    /**
     * Seconds from the reservation's creation to its confirmation, for visits at CONFIRMED or later. The confirmation
     * time is the visit's CONFIRMED event; a visit without one falls back to the reservation's last update.
     */
    public List<Long> confirmSeconds(Instant from, Instant to) {
        String stages = String.join(", ", CONFIRMED_OR_LATER.stream().map(stage -> "'" + stage + "'").toList());
        List<Long> seconds = new ArrayList<>();
        jdbc.query("SELECT r.created_at, r.updated_at, (SELECT min(e.occurred_at) FROM guest_visit_events e "
                + "WHERE e.visit_id = v.id AND e.kind = 'CONFIRMED') AS confirmed_at "
                + "FROM guest_visits v JOIN table_reservation_orders r ON r.id = v.reservation_id "
                + "WHERE v.stage IN (" + stages + ") AND" + IN_WINDOW, rs -> {
            Instant created = instant(rs, "created_at");
            Instant confirmed = instant(rs, "confirmed_at");
            if (confirmed == null) {
                confirmed = instant(rs, "updated_at");
            }
            if (created != null && confirmed != null && !confirmed.isBefore(created)) {
                seconds.add(confirmed.getEpochSecond() - created.getEpochSecond());
            }
        }, ts(from), ts(to));
        return seconds;
    }

    /** Visits whose reservation carries a business-lunch bill (the stored form of a lunch order). */
    public long lunchVisits(Instant from, Instant to) {
        return count("SELECT count(*) FROM guest_visits v WHERE" + IN_WINDOW + "AND EXISTS (SELECT 1 FROM guest_bills b "
                + "WHERE b.kind = 'BUSINESS_LUNCH' AND b.table_reservation_id = v.reservation_id)", from, to);
    }

    public long reviewedVisits(Instant from, Instant to) {
        return count("SELECT count(*) FROM guest_visits v WHERE" + IN_WINDOW + "AND EXISTS (SELECT 1 FROM guest_visit_events e "
                + "WHERE e.visit_id = v.id AND e.kind = 'REVIEW')", from, to);
    }

    /** Visits the guest came back after (the later visit is linked as {@code returned_visit_id}). */
    public long returnedVisits(Instant from, Instant to) {
        return count("SELECT count(*) FROM guest_visits v WHERE" + IN_WINDOW + "AND v.returned_visit_id IS NOT NULL", from, to);
    }

    /** Ledger FALLBACK turns written in the window. */
    public long fallbackTurns(Instant from, Instant to) {
        return count("SELECT count(*) FROM visit_turns WHERE kind = 'FALLBACK' AND at >= ? AND at < ?", from, to);
    }

    /** Audited model calls in the window; an error is a failed call or one answered by the fallback. */
    public ModelTotals modelTotals(Instant from, Instant to) {
        return jdbc.queryForObject("SELECT count(*) AS calls, COALESCE(sum(CASE WHEN success = FALSE OR fallback_used = TRUE "
                        + "THEN 1 ELSE 0 END), 0) AS errors FROM model_interaction_audit WHERE created_at >= ? AND created_at < ?",
                (rs, row) -> new ModelTotals(rs.getLong("calls"), rs.getLong("errors")), ts(from), ts(to));
    }

    /** The 95th percentile (nearest rank) of audited model latency in the window; null without measured calls. */
    public Long modelP95Ms(Instant from, Instant to) {
        long measured = count("SELECT count(*) FROM model_interaction_audit WHERE latency_ms IS NOT NULL "
                + "AND created_at >= ? AND created_at < ?", from, to);
        if (measured == 0) {
            return null;
        }
        long rank = (long) Math.ceil(0.95 * measured);
        return jdbc.queryForObject("SELECT latency_ms FROM model_interaction_audit WHERE latency_ms IS NOT NULL "
                + "AND created_at >= ? AND created_at < ? ORDER BY latency_ms LIMIT 1 OFFSET ?", Long.class, ts(from), ts(to), rank - 1);
    }

    /** Whether each of the latest audited model calls succeeded, newest first. */
    public List<Boolean> lastModelOutcomes(int limit) {
        // Two reads that each follow the (success, created_at) index instead of sorting the whole audit table.
        record Outcome(boolean success, Instant at) {
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (boolean success : new boolean[]{true, false}) {
            outcomes.addAll(jdbc.query("SELECT success, created_at FROM model_interaction_audit WHERE success = ? "
                            + "ORDER BY created_at DESC LIMIT ?",
                    (rs, row) -> new Outcome(rs.getBoolean("success"), instant(rs, "created_at")), success, limit));
        }
        return outcomes.stream().sorted(Comparator.comparing(Outcome::at).reversed()).limit(limit).map(Outcome::success).toList();
    }

    /** Saby calls in the window that failed: an HTTP or IO error, or any status outside 2xx. */
    public long sabyErrors(Instant from, Instant to) {
        return count("SELECT count(*) FROM saby_call_log WHERE occurred_at >= ? AND occurred_at < ? AND " + SABY_FAILED, from, to);
    }

    public SabyTotals sabySince(Instant since) {
        return jdbc.queryForObject("SELECT count(*) AS calls, COALESCE(sum(CASE WHEN " + SABY_FAILED
                        + " THEN 1 ELSE 0 END), 0) AS failures FROM saby_call_log WHERE occurred_at >= ?",
                (rs, row) -> new SabyTotals(rs.getLong("calls"), rs.getLong("failures")), ts(since));
    }

    private long count(String sql, Instant from, Instant to) {
        Long count = jdbc.queryForObject(sql, Long.class, ts(from), ts(to));
        return count == null ? 0 : count;
    }

    private Map<String, Object> map(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
