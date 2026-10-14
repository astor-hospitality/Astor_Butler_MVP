package museon_online.astor_butler.domain.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Visit summaries, one per visit. Every call runs in its own transaction, like the journey and the ledger. */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class VisitSummaryRepository {

    private static final String COLUMNS = "s.visit_id, s.guest_wanted, s.venue_offered, s.outcome, s.stuck, s.next_time_guest, "
            + "s.next_time_agents, s.created_at";
    private static final String GUEST_ORDER = " ORDER BY COALESCE(v.visit_day, v.opened_at) DESC, v.id DESC LIMIT ?";
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VisitSummaryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Writes the visit's summary, replacing the one it had. */
    public void save(VisitSummary summary) {
        jdbc.update("DELETE FROM visit_summaries WHERE visit_id = ?", summary.visitId());
        jdbc.update("INSERT INTO visit_summaries(visit_id, guest_wanted, venue_offered, outcome, stuck, next_time_guest, "
                        + "next_time_agents) VALUES (?, ?, ?, ?, ?, ?, ?)",
                summary.visitId(), summary.guestWanted(), summary.venueOffered(), summary.outcome(), json(summary.stuck()),
                json(summary.nextTimeGuest()), json(summary.nextTimeAgents()));
    }

    public Optional<VisitSummary> find(long visitId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visit_summaries s WHERE s.visit_id = ?", summary(), visitId)
                .stream().findFirst();
    }

    /** Which of these visits have a summary. */
    public Set<Long> withSummary(Collection<Long> visitIds) {
        if (visitIds.isEmpty()) {
            return Set.of();
        }
        String in = visitIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return new HashSet<>(jdbc.queryForList("SELECT visit_id FROM visit_summaries WHERE visit_id IN (" + in + ")",
                Long.class, visitIds.toArray()));
    }

    /**
     * Finished visits (AFTER or RETURNED) with ledger turns and no summary yet, oldest first, leaving out
     * {@code skipped} (visits that failed too often in this run of the service).
     */
    public List<Long> awaitingSummary(Collection<Long> skipped, int limit) {
        String notSkipped = skipped.isEmpty() ? ""
                : " AND v.id NOT IN (" + skipped.stream().map(id -> "?").collect(Collectors.joining(", ")) + ")";
        Object[] args = new Object[skipped.size() + 1];
        System.arraycopy(skipped.toArray(), 0, args, 0, skipped.size());
        args[skipped.size()] = limit;
        return jdbc.queryForList("SELECT v.id FROM guest_visits v WHERE v.stage IN ('AFTER', 'RETURNED')"
                + " AND NOT EXISTS (SELECT 1 FROM visit_summaries s WHERE s.visit_id = v.id)"
                + " AND EXISTS (SELECT 1 FROM visit_turns t WHERE t.visit_id = v.id)" + notSkipped
                + " ORDER BY v.id LIMIT ?", Long.class, args);
    }

    /** The guest's latest summaries by phone, newest visit first. */
    public List<VisitSummary> latestByPhone(String phone, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visit_summaries s JOIN guest_visits v ON v.id = s.visit_id "
                + "WHERE v.guest_phone = ?" + GUEST_ORDER, summary(), phone, limit);
    }

    /** The guest's latest summaries by Telegram chat, newest visit first. */
    public List<VisitSummary> latestByChat(long chatId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visit_summaries s JOIN guest_visits v ON v.id = s.visit_id "
                + "WHERE v.chat_id = ?" + GUEST_ORDER, summary(), chatId, limit);
    }

    private String json(List<String> items) {
        try {
            return mapper.writeValueAsString(items == null ? List.of() : items);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Summary list is not JSON-serializable", e);
        }
    }

    private List<String> list(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private RowMapper<VisitSummary> summary() {
        return (rs, row) -> {
            OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
            return new VisitSummary(rs.getLong("visit_id"), rs.getString("guest_wanted"), rs.getString("venue_offered"),
                    rs.getString("outcome"), list(rs.getString("stuck")), list(rs.getString("next_time_guest")),
                    list(rs.getString("next_time_agents")), created == null ? null : created.toInstant());
        };
    }
}
