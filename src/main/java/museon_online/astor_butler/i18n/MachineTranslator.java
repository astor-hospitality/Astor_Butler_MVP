package museon_online.astor_butler.i18n;

import java.util.List;
import java.util.Optional;

/**
 * Port to a paid translation provider (Yandex Translate in phase 2). Implementations must be safe to call from the
 * reply path: bounded timeout, no retries that block a guest for long, and an empty answer instead of an exception
 * when the provider is down, so the caller falls back to a catalog language.
 */
public interface MachineTranslator {

    /** Short name for logs, cache rows and audit: {@code none}, {@code yandex-translate}. */
    String provider();

    /** False when nothing may be sent: not configured, switched off, or the no-op. */
    boolean available();

    Optional<TranslationResult> translate(TranslationRequest request);

    /** Paid language detection where the provider has it; empty by default. */
    default Optional<DetectedLanguage> detectLanguage(String text, List<String> hints) {
        return Optional.empty();
    }
}
