package museon_online.astor_butler.i18n;

import java.util.Optional;

/**
 * Where a guest's language survives between messages: the explicit choice and the last detected language.
 * The shipped implementation remembers nothing; the database-backed one is phase 1 of the plan
 * ({@code guest_language_preferences}).
 */
public interface GuestLanguagePreferenceStore {

    Optional<String> explicitLanguage(GuestLanguageKey key);

    Optional<String> conversationLanguage(GuestLanguageKey key);

    void rememberConversationLanguage(GuestLanguageKey key, String language);

    void saveExplicitLanguage(GuestLanguageKey key, String language);

    /** Remembers nothing and knows nothing; every lookup is empty. */
    static GuestLanguagePreferenceStore none() {
        return new GuestLanguagePreferenceStore() {
            @Override
            public Optional<String> explicitLanguage(GuestLanguageKey key) {
                return Optional.empty();
            }

            @Override
            public Optional<String> conversationLanguage(GuestLanguageKey key) {
                return Optional.empty();
            }

            @Override
            public void rememberConversationLanguage(GuestLanguageKey key, String language) {
            }

            @Override
            public void saveExplicitLanguage(GuestLanguageKey key, String language) {
            }
        };
    }
}
