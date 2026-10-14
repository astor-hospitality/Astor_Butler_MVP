package museon_online.astor_butler.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Commitment calls by their {@code Idempotency-Key}. A call first claims its key (a row without a result), then runs
 * the tool and completes the row; a second call with the key finds the row and gets its result back, or is refused
 * while the first is still running. Every call runs in its own transaction, so a claim or a result outlives whatever
 * the caller rolls back. A second claim of a key is a unique-key violation the registry turns into the first result.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class IdempotencyStore {

    public static final int MAX_KEY = 80;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** A claimed key: the tool that claimed it and its result, null while the call is in progress. */
    public record Call(String tool, CapabilityResult result) {
        public boolean inProgress() {
            return result == null;
        }
    }

    public Optional<Call> lookup(String key) {
        return jdbc.query("SELECT tool, result FROM capability_calls WHERE idempotency_key = ?",
                (rs, row) -> new Call(rs.getString("tool"), read(rs.getString("result"))), key).stream().findFirst();
    }

    /** The finished result of the key; empty when the key is unknown or still in progress. */
    public Optional<CapabilityResult> find(String key) {
        return lookup(key).map(Call::result);
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the key was claimed already */
    public void claim(String key, String tool) {
        jdbc.update("INSERT INTO capability_calls(idempotency_key, tool) VALUES (?, ?)", key, tool);
    }

    /** Writes the result of a claimed key. */
    public void complete(String key, CapabilityResult result) {
        jdbc.update("UPDATE capability_calls SET result = ? WHERE idempotency_key = ?", write(result), key);
    }

    /** Claims and completes at once. @throws org.springframework.dao.DuplicateKeyException when the key was claimed already */
    public void save(String key, String tool, CapabilityResult result) {
        jdbc.update("INSERT INTO capability_calls(idempotency_key, tool, result) VALUES (?, ?, ?)", key, tool, write(result));
    }

    private String write(CapabilityResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Capability result is not JSON-serializable", e);
        }
    }

    private CapabilityResult read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, CapabilityResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Saved capability result is not readable", e);
        }
    }
}
