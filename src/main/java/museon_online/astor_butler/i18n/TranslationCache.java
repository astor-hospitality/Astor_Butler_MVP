package museon_online.astor_butler.i18n;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Machine translations by source text and language pair. The shipped cache lives in memory; phase 1 of the plan
 * moves it to {@code i18n_translations} in PostgreSQL with a review status, so staff can correct a translation
 * once and every guest gets the corrected text.
 */
public interface TranslationCache {

    Optional<String> get(Key key);

    void put(Key key, String translation, String provider);

    /**
     * @param sourceLanguage normalized language of the source text
     * @param targetLanguage normalized language of the translation
     * @param format         plain or HTML
     * @param sourceHash     SHA-256 of the source text, hex
     */
    record Key(String sourceLanguage, String targetLanguage, TextFormat format, String sourceHash) {

        public static Key of(String sourceText, String sourceLanguage, String targetLanguage, TextFormat format) {
            return new Key(
                    LanguageTags.normalize(sourceLanguage),
                    LanguageTags.normalize(targetLanguage),
                    format == null ? TextFormat.PLAIN : format,
                    sha256(sourceText == null ? "" : sourceText)
            );
        }

        private static String sha256(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }
    }
}
