package museon_online.astor_butler.i18n;

import java.util.List;
import java.util.Optional;

/** Says which language a guest message is written in, or nothing when it cannot tell. */
public interface LanguageDetector {

    /**
     * @param text  the guest message as typed (or as transcribed from voice)
     * @param hints languages the guest is likely to use (platform language, earlier choice), best first;
     *              used only to break ties between languages that share a script
     */
    Optional<DetectedLanguage> detect(String text, List<String> hints);
}
