package museon_online.astor_butler.api.glasses.tasks;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The storage port kept in a map, for tests only: no bucket is needed, and a test can take the storage
 * down or look at what was written. It lives under src/test on purpose, so a running service can never
 * count a photo that sits in memory.
 */
final class InMemoryStaffEvidenceStorage implements StaffEvidenceStorage {
    final Map<String, byte[]> objects = new LinkedHashMap<>();
    final Map<String, String> types = new LinkedHashMap<>();
    boolean down;
    int puts;

    @Override public void put(String key, byte[] bytes, String contentType) {
        if (down) throw StaffEvidenceStorage.unavailable();
        puts++;
        objects.put(key, bytes.clone());
        types.put(key, contentType);
    }

    @Override public Optional<byte[]> get(String key, int limit) {
        if (down) throw StaffEvidenceStorage.unavailable();
        byte[] bytes = objects.get(key);
        if (bytes == null) return Optional.empty();
        if (bytes.length > limit) throw StaffEvidenceStorage.unavailable();
        return Optional.of(bytes.clone());
    }

    /* ---------- Photos made in code: a real signature and filler, never a picture file in the repository ---------- */

    static byte[] jpeg(int size) {
        byte[] bytes = filled(size);
        bytes[0] = (byte) 0xff; bytes[1] = (byte) 0xd8; bytes[2] = (byte) 0xff; bytes[3] = (byte) 0xe0;
        bytes[size - 2] = (byte) 0xff; bytes[size - 1] = (byte) 0xd9;
        return bytes;
    }

    static byte[] png() {
        byte[] bytes = filled(64);
        put(bytes, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
        return bytes;
    }

    static byte[] webp() {
        byte[] bytes = filled(64);
        put(bytes, 0, "RIFF".getBytes(StandardCharsets.US_ASCII));
        put(bytes, 8, "WEBPVP8 ".getBytes(StandardCharsets.US_ASCII));
        return bytes;
    }

    static byte[] heic() {
        return isoMedia("heic");
    }

    /** An ISO media file whose first box names the given brand: heic for an iPhone photo, isom for a video. */
    static byte[] isoMedia(String brand) {
        byte[] bytes = filled(64);
        put(bytes, 0, ByteBuffer.allocate(4).putInt(24).array());
        put(bytes, 4, ("ftyp" + brand).getBytes(StandardCharsets.US_ASCII));
        put(bytes, 12, new byte[]{0, 0, 0, 0});
        put(bytes, 16, "mif1heic".getBytes(StandardCharsets.US_ASCII));
        return bytes;
    }

    private static byte[] filled(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }

    private static void put(byte[] target, int at, byte[] part) {
        System.arraycopy(part, 0, target, at, part.length);
    }
}
