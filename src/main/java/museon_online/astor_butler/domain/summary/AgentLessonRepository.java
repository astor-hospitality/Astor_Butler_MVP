package museon_online.astor_butler.domain.summary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lessons for the Concierge and Butler agents: proposed by visit summaries, accepted (possibly edited) or rejected by a
 * manager on the board. Only accepted lessons reach the agents. Every call runs in its own transaction.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class AgentLessonRepository {

    static final int MAX_TEXT = 1000;
    /** The newest accepted lessons an agent gets; older ones stay on the board. */
    static final int MAX_ACCEPTED = 50;
    private static final String COLUMNS = "id, visit_id, side, text, status, created_at, decided_at";

    private final JdbcTemplate jdbc;

    public AgentLessonRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Proposes a lesson; the same text for the same visit and agent is not proposed twice (returns false). */
    public boolean propose(long visitId, String side, String text) {
        String clipped = clip(requireText(text));
        return jdbc.update("INSERT INTO agent_lessons(visit_id, side, text, status) SELECT ?, ?, ?, ? WHERE NOT EXISTS "
                        + "(SELECT 1 FROM agent_lessons WHERE visit_id = ? AND side = ? AND text = ?)",
                visitId, requireSide(side), clipped, AgentLesson.Status.PROPOSED.name(), visitId, side, clipped) > 0;
    }

    /** A lesson a manager wrote: accepted at once. */
    public void addAccepted(long visitId, String side, String text) {
        jdbc.update("INSERT INTO agent_lessons(visit_id, side, text, status, decided_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                visitId, requireSide(side), clip(requireText(text)), AgentLesson.Status.ACCEPTED.name());
    }

    /** Accepts the lesson; a non-blank {@code editedText} replaces its text. False when there is no such lesson. */
    public boolean accept(long id, String editedText) {
        if (editedText == null || editedText.isBlank()) {
            return jdbc.update("UPDATE agent_lessons SET status = ?, decided_at = CURRENT_TIMESTAMP WHERE id = ?",
                    AgentLesson.Status.ACCEPTED.name(), id) > 0;
        }
        return jdbc.update("UPDATE agent_lessons SET status = ?, text = ?, decided_at = CURRENT_TIMESTAMP WHERE id = ?",
                AgentLesson.Status.ACCEPTED.name(), clip(editedText.trim()), id) > 0;
    }

    public boolean reject(long id) {
        return jdbc.update("UPDATE agent_lessons SET status = ?, decided_at = CURRENT_TIMESTAMP WHERE id = ?",
                AgentLesson.Status.REJECTED.name(), id) > 0;
    }

    /** Texts of the agent's accepted lessons, newest decision first. */
    public List<String> accepted(String side) {
        return jdbc.queryForList("SELECT text FROM agent_lessons WHERE side = ? AND status = ? "
                + "ORDER BY decided_at DESC, id DESC LIMIT ?", String.class, side, AgentLesson.Status.ACCEPTED.name(), MAX_ACCEPTED);
    }

    public List<AgentLesson> withStatus(AgentLesson.Status status) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_lessons WHERE status = ? ORDER BY id DESC LIMIT 500",
                LESSON, status.name());
    }

    public Optional<AgentLesson> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_lessons WHERE id = ?", LESSON, id).stream().findFirst();
    }

    private static String requireSide(String side) {
        if (!AgentLesson.isSide(side)) {
            throw new IllegalArgumentException("A lesson is for CONCIERGE or BUTLER");
        }
        return side;
    }

    private static String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A lesson needs text");
        }
        return text.trim();
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<AgentLesson> LESSON = (rs, row) -> {
        long visitId = rs.getLong("visit_id");
        return new AgentLesson(rs.getLong("id"), rs.wasNull() ? null : visitId, rs.getString("side"), rs.getString("text"),
                AgentLesson.Status.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "decided_at"));
    };
}
