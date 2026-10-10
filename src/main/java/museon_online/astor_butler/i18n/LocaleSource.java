package museon_online.astor_butler.i18n;

/** Why a guest is answered in a language, from the strongest signal to the weakest. */
public enum LocaleSource {
    /** The guest picked the language (a /language command, a site switch). */
    EXPLICIT,
    /** The current message is clearly written in this language. */
    DETECTED,
    /** The last language detected in this conversation; keeps "ok" or "19:00" from flipping the language. */
    CONVERSATION,
    /** The client's interface language: Telegram {@code language_code}, web {@code viewport.locale}. */
    PLATFORM,
    /** Nothing better is known, or the feature is off. */
    DEFAULT
}
