package museon_online.astor_butler.api.glasses.tasks;

import java.util.Set;

/** What a file really is, read from its first bytes. Signatures only: nothing is recognised yet. */
final class StaffEvidenceImages {
    static final String JPEG = "image/jpeg", PNG = "image/png", WEBP = "image/webp", HEIC = "image/heic", HEIF = "image/heif";
    static final Set<String> TYPES = Set.of(JPEG, PNG, WEBP, HEIC, HEIF);

    private StaffEvidenceImages() { }

    /** The image type these bytes are, or null when they are none of the supported ones. */
    static String detect(byte[] bytes) {
        return null;
    }

    static boolean matches(String declared, byte[] bytes) {
        return false;
    }

    static String extension(String type) {
        return "bin";
    }
}
