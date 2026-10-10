package museon_online.astor_butler.i18n;

import java.util.List;

/**
 * Texts to translate in one call. Templates are sent with their {@code {placeholders}}, never with guest data,
 * so no personal data leaves the service through translation of catalog texts.
 *
 * @param texts          one or more texts, translated independently
 * @param sourceLanguage normalized language of the texts
 * @param targetLanguage normalized language wanted
 * @param format         {@link TextFormat#HTML} keeps tags intact
 */
public record TranslationRequest(List<String> texts, String sourceLanguage, String targetLanguage, TextFormat format) {

    public TranslationRequest {
        texts = texts == null ? List.of() : List.copyOf(texts);
        sourceLanguage = LanguageTags.normalize(sourceLanguage);
        targetLanguage = LanguageTags.normalize(targetLanguage);
        format = format == null ? TextFormat.PLAIN : format;
    }

    public static TranslationRequest single(String text, String sourceLanguage, String targetLanguage) {
        return new TranslationRequest(List.of(text), sourceLanguage, targetLanguage, TextFormat.of(text));
    }
}
