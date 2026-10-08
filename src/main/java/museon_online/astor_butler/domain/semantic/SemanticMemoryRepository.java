package museon_online.astor_butler.domain.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class SemanticMemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Optional<String> sourceCodeForMediaAsset(String assetCode) {
        if (assetCode == null || assetCode.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT source_code
                        FROM semantic_sources
                        WHERE media_asset_code = ?
                          AND active = TRUE
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getString("source_code"),
                assetCode
        ).stream().findFirst();
    }

    public UUID upsertChunk(SemanticChunkSeed seed) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO semantic_chunks (
                    source_id, chunk_key, chunk_index, language_code, title, content,
                    token_count, metadata_json, created_at, updated_at
                )
                SELECT source_id, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM semantic_sources
                WHERE source_code = ?
                ON CONFLICT (source_id, chunk_key)
                DO UPDATE SET
                    chunk_index = EXCLUDED.chunk_index,
                    language_code = EXCLUDED.language_code,
                    title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    token_count = EXCLUDED.token_count,
                    metadata_json = EXCLUDED.metadata_json,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING chunk_id
                """,
                UUID.class,
                seed.chunkKey(),
                seed.chunkIndex(),
                blankDefault(seed.languageCode(), "ru"),
                seed.title(),
                seed.content(),
                estimateTokens(seed.content()),
                jsonb(seed.metadata()),
                seed.sourceCode()
        );
    }

    public void upsertEmbedding(UUID chunkId, String model, List<Double> embedding) {
        if (chunkId == null || embedding == null || embedding.isEmpty()) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO semantic_embeddings (
                    chunk_id, embedding_model, embedding_dimension, embedding, created_at, updated_at
                )
                VALUES (?, ?, ?, ?::vector, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (chunk_id)
                DO UPDATE SET
                    embedding_model = EXCLUDED.embedding_model,
                    embedding_dimension = EXCLUDED.embedding_dimension,
                    embedding = EXCLUDED.embedding,
                    updated_at = CURRENT_TIMESTAMP
                """,
                chunkId,
                model,
                embedding.size(),
                vectorLiteral(embedding)
        );
    }

    /**
     * Nearest chunks among rows written by {@code embeddingModel} with the same dimension: vectors of another model
     * or size are never compared, so a provider switch is safe before the re-index has finished.
     */
    public List<SemanticSearchResult> searchNearest(String venueCode, List<String> sourceCodes, String embeddingModel,
                                                    List<Double> embedding, int limit) {
        if (embedding == null || embedding.isEmpty() || embeddingModel == null || embeddingModel.isBlank()) {
            return List.of();
        }
        if (sourceCodes == null || sourceCodes.isEmpty()) {
            return jdbcTemplate.query("""
                            SELECT sc.chunk_id, ss.source_code, ss.source_type, COALESCE(sc.title, ss.title) AS title,
                                   sc.content, 1 - (se.embedding <=> ?::vector) AS score
                            FROM semantic_embeddings se
                            JOIN semantic_chunks sc ON sc.chunk_id = se.chunk_id
                            JOIN semantic_sources ss ON ss.source_id = sc.source_id
                            WHERE ss.venue_code = ?
                              AND ss.active = TRUE
                              AND se.embedding_dimension = ?
                              AND se.embedding_model = ?
                            ORDER BY se.embedding <=> ?::vector
                            LIMIT ?
                            """,
                    resultMapper(),
                    vectorLiteral(embedding),
                    normalizeVenue(venueCode),
                    embedding.size(),
                    embeddingModel,
                    vectorLiteral(embedding),
                    Math.max(1, limit)
            );
        }
        String placeholders = sourceCodes.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        String sql = """
                        SELECT sc.chunk_id, ss.source_code, ss.source_type, COALESCE(sc.title, ss.title) AS title,
                               sc.content, 1 - (se.embedding <=> ?::vector) AS score
                        FROM semantic_embeddings se
                        JOIN semantic_chunks sc ON sc.chunk_id = se.chunk_id
                        JOIN semantic_sources ss ON ss.source_id = sc.source_id
                        WHERE ss.venue_code = ?
                          AND ss.active = TRUE
                          AND se.embedding_dimension = ?
                          AND se.embedding_model = ?
                          AND ss.source_code IN (%s)
                        ORDER BY se.embedding <=> ?::vector
                        LIMIT ?
                        """.formatted(placeholders);
        List<Object> args = new java.util.ArrayList<>();
        args.add(vectorLiteral(embedding));
        args.add(normalizeVenue(venueCode));
        args.add(embedding.size());
        args.add(embeddingModel);
        args.addAll(sourceCodes);
        args.add(vectorLiteral(embedding));
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql, resultMapper(), args.toArray());
    }

    public List<SemanticSearchResult> searchText(String venueCode, List<String> sourceCodes, String query, int limit) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        String sourceFilter = "";
        List<Object> args = new java.util.ArrayList<>();
        args.add(normalizeVenue(venueCode));
        if (sourceCodes != null && !sourceCodes.isEmpty()) {
            sourceFilter = "AND ss.source_code IN (%s)".formatted(
                    sourceCodes.stream().map(ignored -> "?").collect(Collectors.joining(", "))
            );
            args.addAll(sourceCodes);
        }

        String tokenFilter = tokens.stream()
                .map(ignored -> "(LOWER(sc.content) LIKE ? OR LOWER(COALESCE(sc.title, ss.title)) LIKE ?)")
                .collect(Collectors.joining(" OR "));
        for (String token : tokens) {
            String pattern = "%" + token + "%";
            args.add(pattern);
            args.add(pattern);
        }
        args.add(Math.max(1, limit));

        String sql = """
                SELECT sc.chunk_id, ss.source_code, ss.source_type, COALESCE(sc.title, ss.title) AS title,
                       sc.content, 0.50::double precision AS score
                FROM semantic_chunks sc
                JOIN semantic_sources ss ON ss.source_id = sc.source_id
                WHERE ss.venue_code = ?
                  AND ss.active = TRUE
                  %s
                  AND (%s)
                ORDER BY sc.updated_at DESC, sc.chunk_index ASC
                LIMIT ?
                """.formatted(sourceFilter, tokenFilter);

        return jdbcTemplate.query(sql, resultMapper(), args.toArray());
    }

    private RowMapper<SemanticSearchResult> resultMapper() {
        return (rs, rowNum) -> new SemanticSearchResult(
                rs.getObject("chunk_id", UUID.class),
                rs.getString("source_code"),
                rs.getString("source_type"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getDouble("score")
        );
    }

    private PGobject jsonb(Map<String, ?> value) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(objectMapper.writeValueAsString(value == null ? Map.of() : value));
            return object;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize semantic chunk metadata", e);
        }
    }

    private String vectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(value -> Double.toString(value == null ? 0.0 : value))
                .reduce("[", (left, right) -> "[".equals(left) ? left + right : left + "," + right) + "]";
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.trim().split("\\s+").length);
    }

    private List<String> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(query.toLowerCase().split("[^\\p{L}\\p{N}_]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .distinct()
                .limit(8)
                .toList();
    }

    private String normalizeVenue(String venueCode) {
        return venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.strip().toUpperCase();
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
