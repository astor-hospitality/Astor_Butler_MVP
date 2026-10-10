package museon_online.astor_butler.i18n;

import java.util.Optional;

/** The default translator: never available, never calls anyone, never costs anything. */
public final class NoOpMachineTranslator implements MachineTranslator {

    public static final String PROVIDER = "none";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Optional<TranslationResult> translate(TranslationRequest request) {
        return Optional.empty();
    }
}
