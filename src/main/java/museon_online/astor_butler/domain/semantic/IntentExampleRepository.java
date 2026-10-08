package museon_online.astor_butler.domain.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IntentExampleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public UUID upsert(IntentExampleSeed seed) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO intent_examples (
                    venue_code, scenario_id, state, intent, phrase, normalized_phrase,
                    expected_slots_json, source, locale, status, weight, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'APPROVED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (venue_code, state, intent, normalized_phrase)
                DO UPDATE SET
                    scenario_id = EXCLUDED.scenario_id,
                    phrase = EXCLUDED.phrase,
                    expected_slots_json = EXCLUDED.expected_slots_json,
                    source = EXCLUDED.source,
                    locale = EXCLUDED.locale,
                    status = 'APPROVED',
                    weight = EXCLUDED.weight,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING example_id
                """,
                UUID.class,
                seed.venueCode(),
                seed.scenarioId(),
                blankToNull(seed.state()),
                seed.intent(),
                seed.phrase(),
                seed.normalizedPhrase(),
                seed.expectedSlotsJson(),
                seed.source(),
                seed.locale(),
                seed.weight()
        );
    }

    public Optional<IntentExampleMatch> findBestLexicalMatch(String venueCode, String state, String rawText) {
        String normalized = SemanticTextNormalizer.normalize(rawText);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT example_id, scenario_id, state, intent, phrase, normalized_phrase,
                               expected_slots_json::text AS expected_slots_json,
                               GREATEST(similarity(normalized_phrase, ?), CASE WHEN normalized_phrase = ? THEN 1 ELSE 0 END) AS score
                        FROM intent_examples
                        WHERE venue_code = ?
                          AND status = 'APPROVED'
                          AND (state IS NULL OR state = ? OR ? IS NULL)
                          AND normalized_phrase % ?
                        ORDER BY score DESC, weight DESC, updated_at DESC
                        LIMIT 1
                        """,
                matchMapper(),
                normalized,
                normalized,
                normalizeVenue(venueCode),
                blankToNull(state),
                blankToNull(state),
                normalized
        ).stream().findFirst();
    }

    /** Nearest examples among rows written by {@code embeddingModel} with the same dimension. */
    public List<IntentExampleMatch> findNearestByEmbedding(String venueCode, String state, String embeddingModel,
                                                           List<Double> embedding, int limit) {
        if (embedding == null || embedding.isEmpty() || embeddingModel == null || embeddingModel.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT ie.example_id, ie.scenario_id, ie.state, ie.intent, ie.phrase, ie.normalized_phrase,
                               ie.expected_slots_json::text AS expected_slots_json,
                               1 - (iee.embedding <=> ?::vector) AS score
                        FROM intent_examples ie
                        JOIN intent_example_embeddings iee ON iee.example_id = ie.example_id
                        WHERE ie.venue_code = ?
                          AND ie.status = 'APPROVED'
                          AND iee.embedding_dimension = ?
                          AND iee.embedding_model = ?
                          AND (ie.state IS NULL OR ie.state = ? OR ? IS NULL)
                        ORDER BY iee.embedding <=> ?::vector
                        LIMIT ?
                        """,
                matchMapper(),
                vectorLiteral(embedding),
                normalizeVenue(venueCode),
                embedding.size(),
                embeddingModel,
                blankToNull(state),
                blankToNull(state),
                vectorLiteral(embedding),
                Math.max(1, limit)
        );
    }

    public void upsertEmbedding(UUID exampleId, String model, List<Double> embedding) {
        if (exampleId == null || embedding == null || embedding.isEmpty()) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO intent_example_embeddings (
                    example_id, embedding_model, embedding_dimension, embedding, created_at, updated_at
                )
                VALUES (?, ?, ?, ?::vector, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (example_id)
                DO UPDATE SET
                    embedding_model = EXCLUDED.embedding_model,
                    embedding_dimension = EXCLUDED.embedding_dimension,
                    embedding = EXCLUDED.embedding,
                    updated_at = CURRENT_TIMESTAMP
                """,
                exampleId,
                model,
                embedding.size(),
                vectorLiteral(embedding)
        );
    }

    public void captureMiss(String venueCode, Long chatId, Long telegramUserId, String state,
                            String rawText, String detectedIntent, double confidence) {
        String normalized = SemanticTextNormalizer.normalize(rawText);
        if (normalized.isBlank()) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO intent_understanding_misses (
                    venue_code, chat_id, telegram_user_id, state, raw_text, normalized_text,
                    detected_intent, confidence, resolution_status, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NEW', CURRENT_TIMESTAMP)
                """,
                normalizeVenue(venueCode),
                chatId,
                telegramUserId,
                blankToNull(state),
                rawText,
                normalized,
                detectedIntent,
                confidence
        );
    }

    public PGobject jsonb(Map<String, ?> value) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(objectMapper.writeValueAsString(value == null ? Map.of() : value));
            return object;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize intent example slots", e);
        }
    }

    private RowMapper<IntentExampleMatch> matchMapper() {
        return (rs, rowNum) -> new IntentExampleMatch(
                rs.getObject("example_id", UUID.class),
                rs.getString("scenario_id"),
                rs.getString("state"),
                rs.getString("intent"),
                rs.getString("phrase"),
                rs.getString("normalized_phrase"),
                rs.getString("expected_slots_json"),
                rs.getDouble("score")
        );
    }

    private String vectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(value -> Double.toString(value == null ? 0.0 : value))
                .reduce("[", (left, right) -> "[".equals(left) ? left + right : left + "," + right) + "]";
    }

    private String normalizeVenue(String venueCode) {
        return venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.strip().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
