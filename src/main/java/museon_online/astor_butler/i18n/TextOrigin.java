package museon_online.astor_butler.i18n;

/** Where a guest text came from; goes to outgoing metadata and later to the review queue. */
public enum TextOrigin {
    /** A human-edited catalog in the guest's own language. */
    CATALOG,
    /** Machine translation of the source catalog (cached). */
    MACHINE_TRANSLATION,
    /** The guest's language was not available; the fallback language was used. */
    FALLBACK,
    /** The key is in no catalog; the key itself was returned. A bug, logged. */
    MISSING
}
