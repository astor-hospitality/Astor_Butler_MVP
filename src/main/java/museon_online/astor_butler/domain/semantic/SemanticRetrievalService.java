package museon_online.astor_butler.domain.semantic;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticRetrievalService {

    private final SemanticMemoryRepository repository;
    private final ObjectProvider<EmbeddingProvider> embeddingProvider;

    public List<SemanticSearchResult> search(String venueCode, String query, List<String> sourceCodes, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            EmbeddingProvider provider = embeddingProvider.getIfAvailable();
            if (provider == null) {
                return List.of();
            }
            List<Double> embedding = provider.embedQuery(query);
            if (embedding.isEmpty()) {
                return List.of();
            }
            return repository.searchNearest(venueCode, sourceCodes, provider.model(), embedding, limit).stream()
                    .filter(result -> result.score() >= 0.42)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn(
                    "Semantic retrieval skipped: venueCode={}, sourceCodes={}, limit={}, reason={}",
                    venueCode,
                    sourceCodes,
                    limit,
                    ex.toString()
            );
            return List.of();
        }
    }

    public List<String> sourceCodesForAssets(List<String> assetCodes) {
        if (assetCodes == null || assetCodes.isEmpty()) {
            return List.of();
        }
        return assetCodes.stream()
                .flatMap(assetCode -> repository.sourceCodeForMediaAsset(assetCode).stream())
                .distinct()
                .toList();
    }
}
