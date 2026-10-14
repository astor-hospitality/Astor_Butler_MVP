package museon_online.astor_butler.domain.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The visit ledger: every turn of a visit in time order. Every call runs in its own transaction, like the guest
 * journey: a ledger write may happen inside a reservation transaction and must never roll it back on PostgreSQL.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class VisitTurnRepository {

    static final int MAX_TEXT = 4000;
    private static final String COLUMNS = "id, visit_id, session_id, at, side, channel, kind, text, payload, goal";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VisitTurnRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long append(VisitTurnDraft draft) {
        String text = draft.text() == null || draft.text().length() <= MAX_TEXT ? draft.text() : draft.text().substring(0, MAX_TEXT);
        String payload = json(draft.payload());
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO visit_turns(visit_id, session_id, side, channel, "
                    + "kind, text, payload, goal) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", new String[]{"id"});
            statement.setObject(1, draft.visitId());
            statement.setString(2, draft.sessionId());
            statement.setString(3, draft.side().name());
            statement.setString(4, draft.channel().name());
            statement.setString(5, draft.kind().name());
            statement.setString(6, text);
            statement.setString(7, payload);
            statement.setString(8, draft.goal());
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) {
            throw new IllegalStateException("visit_turns insert returned no id");
        }
        return id.longValue();
    }

    /** Moves the session's turns that have no visit yet into the visit; returns how many moved. */
    public int linkSession(String sessionId, long visitId) {
        return jdbc.update("UPDATE visit_turns SET visit_id = ? WHERE session_id = ? AND visit_id IS NULL", visitId, sessionId);
    }

    /** Whether the Concierge session has a turn in the visit, i.e. was linked to it. */
    public boolean existsLink(long visitId, String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM visit_turns WHERE visit_id = ? AND session_id = ?",
                Integer.class, visitId, sessionId);
        return count != null && count > 0;
    }

    /** The visit the Concierge session was linked to (the latest one); empty while it has none. */
    public Optional<Long> visitOfSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        List<Long> found = jdbc.queryForList("SELECT visit_id FROM visit_turns WHERE session_id = ? AND visit_id IS NOT NULL "
                + "ORDER BY id DESC LIMIT 1", Long.class, sessionId);
        return found.stream().findFirst();
    }

    /** A Concierge session linked to the visit (the latest turn that has one); empty when the visit has none. */
    public Optional<String> sessionOf(long visitId) {
        List<String> found = jdbc.queryForList("SELECT session_id FROM visit_turns WHERE visit_id = ? AND session_id IS NOT NULL "
                + "ORDER BY id DESC LIMIT 1", String.class, visitId);
        return found.stream().findFirst();
    }

    public List<VisitTurn> ofVisit(long visitId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visit_turns WHERE visit_id = ? ORDER BY at, id", turn(), visitId);
    }

    public List<VisitTurn> ofSession(String sessionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visit_turns WHERE session_id = ? ORDER BY at, id", turn(), sessionId);
    }

    public int countGuestMessages(long visitId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM visit_turns WHERE visit_id = ? AND side = ? AND kind = ?",
                Integer.class, visitId, TurnSide.GUEST.name(), TurnKind.MESSAGE.name());
        return count == null ? 0 : count;
    }

    private String json(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Turn payload is not JSON-serializable", e);
        }
    }

    private Map<String, Object> map(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    private RowMapper<VisitTurn> turn() {
        return (rs, row) -> new VisitTurn(
                rs.getLong("id"),
                nullableLong(rs, "visit_id"),
                rs.getString("session_id"),
                rs.getObject("at", OffsetDateTime.class).toInstant(),
                TurnSide.valueOf(rs.getString("side")),
                TurnChannel.valueOf(rs.getString("channel")),
                TurnKind.valueOf(rs.getString("kind")),
                rs.getString("text"),
                map(rs.getString("payload")),
                rs.getString("goal"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
