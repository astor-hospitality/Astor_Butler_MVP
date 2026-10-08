package museon_online.astor_butler.domain.semantic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup check of the pgvector store against {@code ASTOR_SEMANTIC_EMBEDDING_DIMENSION}, run before the ingest
 * bootstraps. Both embedding columns are plain {@code vector} without a fixed size since changeset
 * {@code 2026-06-29-semantic-rag-runtime}, and every row records its {@code embedding_dimension}, which search filters
 * on; so switching to a model with another size needs no migration, only a re-index.
 *
 * <ul>
 *     <li>A column pinned to a different size ({@code vector(N)}, e.g. altered by hand) would reject every insert:
 *     startup fails with the one-shot SQL that fixes it.</li>
 *     <li>Rows written by another model or with another size are never compared with new vectors; they are reported
 *     with the flag that re-embeds them on this startup.</li>
 * </ul>
 * Nothing runs when no {@link EmbeddingProvider} is configured; a database without pgvector only logs a warning.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SemanticEmbeddingStoreCheck implements ApplicationRunner {

    record Store(String table, String ingestFlag, boolean ingestOnStartup) {
    }

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;
    private final ObjectProvider<EmbeddingProvider> embeddingProvider;
    private final int expectedDimension;
    private final List<Store> stores;

    public SemanticEmbeddingStoreCheck(
            ObjectProvider<JdbcTemplate> jdbcTemplate,
            ObjectProvider<EmbeddingProvider> embeddingProvider,
            @Value("${astor.semantic-memory.pgvector.embedding-dimension:1536}") int expectedDimension,
            @Value("${astor.semantic-memory.chunks.ingest-on-startup:false}") boolean chunksOnStartup,
            @Value("${astor.semantic-memory.intent-examples.ingest-golden-corpus-on-startup:false}") boolean examplesOnStartup
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
        this.expectedDimension = expectedDimension;
        this.stores = List.of(
                new Store("semantic_embeddings", "ASTOR_SEMANTIC_CHUNKS_INGEST_ON_STARTUP", chunksOnStartup),
                new Store("intent_example_embeddings", "ASTOR_INTENT_EXAMPLES_INGEST_ON_STARTUP", examplesOnStartup)
        );
    }

    @Override
    public void run(ApplicationArguments args) {
        EmbeddingProvider provider = embeddingProvider.getIfAvailable();
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        if (provider == null || jdbc == null) {
            return;
        }
        for (Store store : stores) {
            check(jdbc, store, provider.model());
        }
    }

    private void check(JdbcTemplate jdbc, Store store, String model) {
        Integer pinnedDimension;
        Long stale;
        try {
            pinnedDimension = jdbc.query("""
                            SELECT a.atttypmod
                            FROM pg_catalog.pg_attribute a
                            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                            WHERE c.relname = ?
                              AND pg_catalog.pg_table_is_visible(c.oid)
                              AND a.attname = 'embedding'
                              AND NOT a.attisdropped
                            """,
                    (rs, rowNum) -> rs.getInt(1),
                    store.table()
            ).stream().findFirst().orElse(null);
            stale = pinnedDimension == null ? null : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + store.table() + " WHERE embedding_dimension <> ? OR embedding_model <> ?",
                    Long.class,
                    expectedDimension,
                    model
            );
        } catch (DataAccessException e) {
            log.warn("Semantic embedding store check skipped: table={} reason={}", store.table(), e.toString());
            return;
        }
        if (pinnedDimension == null) {
            log.warn("Semantic embedding store check: table {} has no embedding column; semantic search will stay empty",
                    store.table());
            return;
        }
        // pgvector keeps the declared size as the type modifier; -1 means a plain `vector` of any size.
        if (pinnedDimension > 0 && pinnedDimension != expectedDimension) {
            throw new IllegalStateException(("%s.embedding is vector(%d) but ASTOR_SEMANTIC_EMBEDDING_DIMENSION=%d. "
                    + "Run once: DELETE FROM %s; ALTER TABLE %s ALTER COLUMN embedding TYPE vector; "
                    + "then restart with %s=true (or set ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=none to start without "
                    + "semantic search)").formatted(store.table(), pinnedDimension, expectedDimension, store.table(),
                    store.table(), store.ingestFlag()));
        }
        if (stale != null && stale > 0) {
            log.warn("Semantic embedding store: {} row(s) in {} come from another model or dimension (now {} / {}); "
                            + "search ignores them. {}",
                    stale, store.table(), model, expectedDimension,
                    store.ingestOnStartup()
                            ? "They are re-embedded by the ingest on this startup."
                            : "Re-index: restart once with " + store.ingestFlag() + "=true.");
        } else {
            log.info("Semantic embedding store ok: table={} model={} dimension={}", store.table(), model, expectedDimension);
        }
    }
}
