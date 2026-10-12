package museon_online.astor_butler.api.glasses.tasks;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * What a file really is, read from its first bytes. A file name or a Content-Type header is only what the
 * sender says. Nothing is decoded or re-encoded here: the stored photo is the photo that was taken.
 */
final class StaffEvidenceImages {
    static final String JPEG = "image/jpeg", PNG = "image/png", WEBP = "image/webp", HEIC = "image/heic", HEIF = "image/heif";
    static final Set<String> TYPES = Set.of(JPEG, PNG, WEBP, HEIC, HEIF);
    private static final Set<String> HEVC_BRANDS = Set.of("heic", "heix", "heim", "heis", "hevc", "hevx");
    private static final Set<String> HEIF_BRANDS = Set.of("mif1", "msf1");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private StaffEvidenceImages() { }

    /** The image type these bytes are, or null when they are none of the supported ones. */
    static String detect(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length >= 4 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff) return JPEG;
        if (bytes.length >= 24 && starts(bytes, 0, PNG_SIGNATURE) && ascii(bytes, 12, 4).equals("IHDR")) return PNG;
        if (bytes.length >= 16 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP")
                && ascii(bytes, 12, 3).equals("VP8")) return WEBP;
        if (bytes.length >= 16 && ascii(bytes, 4, 4).equals("ftyp")) {
            // The first box of an ISO media file names its brand; its size must fit the file it came in.
            long box = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());
            if (box < 16 || box > bytes.length) return null;
            String brand = ascii(bytes, 8, 4);
            if (HEVC_BRANDS.contains(brand)) return HEIC;
            if (HEIF_BRANDS.contains(brand)) return HEIF;
        }
        return null;
    }

    /** True when the bytes are the declared type. HEIC and HEIF are one container, so either name fits either. */
    static boolean matches(String declared, byte[] bytes) {
        String real = detect(bytes);
        if (real == null || declared == null) return false;
        return real.equals(declared) || (heif(real) && heif(declared));
    }

    static String extension(String type) {
        return switch (type == null ? "" : type) {
            case JPEG -> "jpg";
            case PNG -> "png";
            case WEBP -> "webp";
            case HEIC -> "heic";
            case HEIF -> "heif";
            default -> "bin";
        };
    }

    private static boolean heif(String type) {
        return HEIC.equals(type) || HEIF.equals(type);
    }

    private static boolean starts(byte[] bytes, int at, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) if (bytes[at + i] != prefix[i]) return false;
        return true;
    }

    private static String ascii(byte[] bytes, int at, int length) {
        return new String(bytes, at, length, StandardCharsets.ISO_8859_1);
    }
}
