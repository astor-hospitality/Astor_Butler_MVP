package museon_online.astor_butler.i18n;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Optional;

/** Bounded in-process cache; lost on restart, which only means one more paid call per text and language. */
public final class InMemoryTranslationCache implements TranslationCache {

    private final Cache<Key, String> cache;

    public InMemoryTranslationCache(int maxEntries) {
        this.cache = Caffeine.newBuilder().maximumSize(Math.max(1, maxEntries)).build();
    }

    @Override
    public Optional<String> get(Key key) {
        return key == null ? Optional.empty() : Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(Key key, String translation, String provider) {
        if (key != null && translation != null && !translation.isBlank()) {
            cache.put(key, translation);
        }
    }
}
