package museon_online.astor_butler.domain.semantic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "astor.semantic-memory.intent-examples", name = "ingest-golden-corpus-on-startup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IntentExampleBootstrap implements ApplicationRunner {

    private final ResourceLoader resourceLoader;
    private final IntentExampleCorpusLoader corpusLoader;
    private final IntentExampleRepository repository;
    private final ObjectProvider<EmbeddingProvider> embeddingProvider;

    @Value("${astor.semantic-memory.intent-examples.golden-corpus:classpath:understanding/guest-input-golden-corpus.jsonl}")
    private String corpusLocation;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Resource corpus = resourceLoader.getResource(corpusLocation);
            List<IntentExampleSeed> examples = corpusLoader.load(corpus);
            EmbeddingProvider provider = embeddingProvider.getIfAvailable();
            boolean embeddingsAvailable = provider != null;
            int embeddings = 0;
            for (IntentExampleSeed example : examples) {
                UUID exampleId = repository.upsert(example);
                if (embeddingsAvailable) {
                    try {
                        List<Double> vector = provider.embed(example.normalizedPhrase());
                        if (!vector.isEmpty()) {
                            repository.upsertEmbedding(exampleId, provider.model(), vector);
                            embeddings++;
                        }
                    } catch (RuntimeException e) {
                        embeddingsAvailable = false;
                        log.warn(
                                "Intent examples embeddings disabled for this startup: corpus={} model={} reason={}",
                                corpusLocation,
                                provider.model(),
                                e.toString()
                        );
                    }
                }
            }
            log.info("Intent examples bootstrapped: examples={}, embeddings={}, model={}, corpus={}",
                    examples.size(), embeddings, provider == null ? "none" : provider.model(), corpusLocation);
        } catch (RuntimeException e) {
            log.warn("Intent examples bootstrap skipped: corpus={} reason={}", corpusLocation, e.toString());
        }
    }
}
