package museon_online.astor_butler.speech;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup with a message naming {@code ASTOR_STT_PROVIDER} when the value is not {@code local},
 * {@code cloudru} or {@code yandex}; otherwise the conditional adapters would silently leave no
 * {@link SpeechToTextService}.
 */
@Component
public class SpeechToTextProviderCheck {

    private final SpeechToTextProvider provider;

    public SpeechToTextProviderCheck(@Value("${astor.speech-to-text.provider:" + SpeechToTextProvider.DEFAULT + "}") String provider) {
        this.provider = SpeechToTextProvider.parse(provider, "ASTOR_STT_PROVIDER");
    }

    public SpeechToTextProvider provider() {
        return provider;
    }
}
