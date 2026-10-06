package museon_online.astor_butler.api.glasses;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded successful replies only. Neither raw inputs nor credentials are retained. */
final class GlassesReplyCache {
    private final Clock clock;
    private final int capacity;
    private final Duration ttl;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

    GlassesReplyCache() { this(Clock.systemUTC(), 32, Duration.ofSeconds(120)); }

    GlassesReplyCache(Clock clock, int capacity, Duration ttl) {
        this.clock = clock;
        this.capacity = capacity;
        this.ttl = ttl;
    }

    synchronized String find(GlassesAccess.Scope scope, String id, String signature) {
        entries.values().removeIf(e -> !clock.instant().isBefore(e.expiresAt));
        Entry entry = entries.get(new Key(scope, UUID.fromString(id)));
        if (entry == null) return null;
        if (!entry.signature.equals(signature)) {
            throw new GlassesFailure(409, "REQUEST_ID_CONFLICT", "Use a new requestId for changed input");
        }
        return entry.answer;
    }

    synchronized void remember(GlassesAccess.Scope scope, String id, String signature, String answer) {
        if (entries.size() >= capacity) entries.remove(entries.keySet().iterator().next());
        entries.put(new Key(scope, UUID.fromString(id)), new Entry(signature, answer, clock.instant().plus(ttl)));
    }

    static String fingerprint(String kind, String text, byte[] media) {
        return digest(kind.getBytes(StandardCharsets.UTF_8), text.getBytes(StandardCharsets.UTF_8), media);
    }

    static String digest(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(ByteBuffer.allocate(4).putInt(part.length).array());
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private record Key(GlassesAccess.Scope scope, UUID requestId) { }
    private record Entry(String signature, String answer, Instant expiresAt) { }
}
