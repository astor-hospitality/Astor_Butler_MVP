package museon_online.astor_butler.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The results of commitment calls by their {@code Idempotency-Key}: a key seen before gets its saved result back and
 * the tool is not run again. Every call runs in its own transaction, so a saved result outlives whatever the caller
 * rolls back. A second save of a key is a unique-key violation the registry answers with the first result.
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

    public Optional<CapabilityResult> find(String key) {
        return jdbc.queryForList("SELECT result FROM capability_calls WHERE idempotency_key = ?", String.class, key)
                .stream().findFirst().map(this::read);
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the key was saved already */
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
        try {
            return mapper.readValue(json, CapabilityResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Saved capability result is not readable", e);
        }
    }
}
