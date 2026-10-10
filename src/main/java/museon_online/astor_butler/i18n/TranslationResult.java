package museon_online.astor_butler.i18n;

import java.util.List;

/**
 * @param texts    translations in the order of the request
 * @param provider who translated, stored next to the cached text ({@code yandex-translate}, ...)
 */
public record TranslationResult(List<String> texts, String provider) {

    public TranslationResult {
        texts = texts == null ? List.of() : List.copyOf(texts);
        provider = provider == null ? "" : provider;
    }
}
