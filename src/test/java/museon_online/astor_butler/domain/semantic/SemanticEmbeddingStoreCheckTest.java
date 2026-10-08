package museon_online.astor_butler.domain.semantic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SemanticEmbeddingStoreCheckTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);

    @SuppressWarnings("unchecked")
    private SemanticEmbeddingStoreCheck check(EmbeddingProvider configured, int dimension) {
        ObjectProvider<JdbcTemplate> jdbcHolder = mock(ObjectProvider.class);
        ObjectProvider<EmbeddingProvider> providerHolder = mock(ObjectProvider.class);
        when(jdbcHolder.getIfAvailable()).thenReturn(jdbc);
        when(providerHolder.getIfAvailable()).thenReturn(configured);
        return new SemanticEmbeddingStoreCheck(jdbcHolder, providerHolder, dimension, true, false);
    }

    @SuppressWarnings("unchecked")
    private void columnTypmod(String table, Integer typmod) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(table)))
                .thenReturn(typmod == null ? List.of() : List.of(typmod));
    }

    @Test
    void doesNothingWithoutAnEmbeddingProvider() {
        assertThatCode(() -> check(null, 256).run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        verifyNoInteractions(jdbc);
    }

    @Test
    void plainVectorColumnsPassAndStaleRowsAreOnlyCounted() {
        when(provider.model()).thenReturn("text-search-doc/latest");
        columnTypmod("semantic_embeddings", -1);
        columnTypmod("intent_example_embeddings", -1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(256), eq("text-search-doc/latest"))).thenReturn(3L);

        assertThatCode(() -> check(provider, 256).run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        verify(jdbc).queryForObject(
                eq("SELECT COUNT(*) FROM semantic_embeddings WHERE embedding_dimension <> ? OR embedding_model <> ?"),
                eq(Long.class), eq(256), eq("text-search-doc/latest"));
        verify(jdbc).queryForObject(
                eq("SELECT COUNT(*) FROM intent_example_embeddings WHERE embedding_dimension <> ? OR embedding_model <> ?"),
                eq(Long.class), eq(256), eq("text-search-doc/latest"));
    }

    @Test
    void columnPinnedToAnotherSizeFailsStartupWithTheOneShotSql() {
        when(provider.model()).thenReturn("text-search-doc/latest");
        columnTypmod("semantic_embeddings", 1536);

        assertThatThrownBy(() -> check(provider, 256).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("semantic_embeddings.embedding is vector(1536)")
                .hasMessageContaining("ASTOR_SEMANTIC_EMBEDDING_DIMENSION=256")
                .hasMessageContaining("ALTER TABLE semantic_embeddings ALTER COLUMN embedding TYPE vector")
                .hasMessageContaining("ASTOR_SEMANTIC_CHUNKS_INGEST_ON_STARTUP=true");
    }

    @Test
    void columnPinnedToTheConfiguredSizeIsAccepted() {
        when(provider.model()).thenReturn("text-search-doc/latest");
        columnTypmod("semantic_embeddings", 256);
        columnTypmod("intent_example_embeddings", 256);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(256), eq("text-search-doc/latest"))).thenReturn(0L);

        assertThatCode(() -> check(provider, 256).run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseErrorsOnlyWarn() {
        when(provider.model()).thenReturn("text-search-doc/latest");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("no pg_catalog"));

        assertThatCode(() -> check(provider, 256).run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }
}
