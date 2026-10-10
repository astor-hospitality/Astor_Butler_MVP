package museon_online.astor_butler.i18n;

/**
 * What a detector believes a text is written in.
 *
 * @param language   normalized primary subtag
 * @param confidence 0..1; the resolver ignores values below {@code astor.i18n.detection.min-confidence}
 * @param method     which detector said so ({@code script}, {@code yandex-translate}, ...), for logs and audit
 */
public record DetectedLanguage(String language, double confidence, String method) {

    public DetectedLanguage {
        language = LanguageTags.normalize(language);
        confidence = Math.min(1.0, Math.max(0.0, confidence));
        method = method == null ? "" : method;
    }
}
