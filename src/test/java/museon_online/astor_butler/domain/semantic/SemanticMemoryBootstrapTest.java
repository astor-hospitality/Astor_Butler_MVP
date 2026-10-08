package museon_online.astor_butler.domain.semantic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticMemoryBootstrapTest {

    private final ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
    private final SemanticMarkdownChunkLoader loader = mock(SemanticMarkdownChunkLoader.class);
    private final SemanticMemoryRepository repository = mock(SemanticMemoryRepository.class);
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);

    @SuppressWarnings("unchecked")
    private SemanticMemoryBootstrap bootstrap() throws Exception {
        Resource resource = mock(Resource.class);
        when(resolver.getResources("classpath*:semantic/**/*.md")).thenReturn(new Resource[]{resource});
        when(loader.load(resource)).thenReturn(List.of(seed("menu-1"), seed("menu-2")));
        when(repository.upsertChunk(any())).thenReturn(UUID.randomUUID());
        when(provider.model()).thenReturn("text-search-doc/latest");
        ObjectProvider<EmbeddingProvider> holder = mock(ObjectProvider.class);
        when(holder.getIfAvailable()).thenReturn(provider);
        SemanticMemoryBootstrap bootstrap = new SemanticMemoryBootstrap(resolver, loader, repository, holder);
        ReflectionTestUtils.setField(bootstrap, "seedLocation", "classpath*:semantic/**/*.md");
        return bootstrap;
    }

    @Test
    void reindexesEveryChunkWithTheCurrentProvider() throws Exception {
        when(provider.embed(anyString())).thenReturn(List.of(0.1, 0.2));

        bootstrap().run(new DefaultApplicationArguments());

        verify(repository, times(2)).upsertEmbedding(any(UUID.class), eq("text-search-doc/latest"), eq(List.of(0.1, 0.2)));
    }

    @Test
    void embeddingFailureKeepsWritingChunks() throws Exception {
        when(provider.embed(anyString())).thenThrow(new IllegalStateException("status=403"));

        assertThatCode(() -> bootstrap().run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        verify(repository, times(2)).upsertChunk(any());
        verify(provider, times(1)).embed(anyString());
        verify(repository, never()).upsertEmbedding(any(), any(), any());
    }

    private SemanticChunkSeed seed(String key) {
        return new SemanticChunkSeed("AERIS_MENU_KITCHEN_SOURCE", key, 0, "ru", "Меню", "Стейк рибай", Map.of());
    }
}
