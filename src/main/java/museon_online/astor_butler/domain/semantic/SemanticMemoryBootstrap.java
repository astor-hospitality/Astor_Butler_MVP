package museon_online.astor_butler.domain.semantic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "astor.semantic-memory.chunks", name = "ingest-on-startup", havingValue = "true")
public class SemanticMemoryBootstrap implements ApplicationRunner {

    private final ResourcePatternResolver resourcePatternResolver;
    private final SemanticMarkdownChunkLoader chunkLoader;
    private final SemanticMemoryRepository repository;
    private final ObjectProvider<EmbeddingProvider> embeddingProvider;

    @Value("${astor.semantic-memory.chunks.seed-location:classpath*:semantic/**/*.md}")
    private String seedLocation;

    /**
     * Upserts every seed chunk and (re-)embeds it with the current provider, which is also how a provider or model
     * switch re-indexes the RAG store. An embedding failure turns embeddings off for the rest of this run; the chunks are
     * still written, so the text fallback keeps working.
     */
    @Override
    public void run(ApplicationArguments args) {
        EmbeddingProvider provider = embeddingProvider.getIfAvailable();
        boolean embeddingsAvailable = provider != null;
        int chunks = 0;
        int embeddings = 0;
        try {
            for (Resource resource : resourcePatternResolver.getResources(seedLocation)) {
                List<SemanticChunkSeed> seeds = chunkLoader.load(resource);
                for (SemanticChunkSeed seed : seeds) {
                    UUID chunkId = repository.upsertChunk(seed);
                    chunks++;
                    if (embeddingsAvailable) {
                        try {
                            List<Double> vector = provider.embed(seed.title() + "\n" + seed.content());
                            if (!vector.isEmpty()) {
                                repository.upsertEmbedding(chunkId, provider.model(), vector);
                                embeddings++;
                            }
                        } catch (RuntimeException e) {
                            embeddingsAvailable = false;
                            log.warn("Semantic RAG embeddings disabled for this startup: model={} reason={}",
                                    provider.model(), e.toString());
                        }
                    }
                }
            }
            log.info("Semantic RAG chunks bootstrapped: chunks={}, embeddings={}, model={}, seedLocation={}",
                    chunks, embeddings, provider == null ? "none" : provider.model(), seedLocation);
        } catch (IOException | RuntimeException e) {
            log.warn("Semantic RAG bootstrap skipped: seedLocation={} reason={}", seedLocation, e.toString());
        }
    }
}
