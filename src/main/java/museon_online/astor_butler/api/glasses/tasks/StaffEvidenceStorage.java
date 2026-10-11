package museon_online.astor_butler.api.glasses.tasks;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Where the bytes of a proof photo live. A port: the portal decides who may write and read, an adapter only
 * keeps private objects under keys the server made. No public links, and a client never names a key.
 */
public interface StaffEvidenceStorage {

    /** Stores the object or throws a 503 {@link StaffTaskFailure}. A photo that was not stored must not be counted. */
    void put(String key, byte[] bytes, String contentType);

    /**
     * The stored bytes, or empty when there is no such object any more. A 503 {@link StaffTaskFailure} when
     * the storage cannot answer or holds more than the limit under this key.
     */
    Optional<byte[]> get(String key, int limit);

    /** One prefix for a venue and, inside it, one for a task. */
    static String taskPrefix(String tenant, String taskId) {
        throw new UnsupportedOperationException("Proof photos are not implemented yet");
    }

    static String key(String tenant, String taskId, String evidenceId, String contentType) {
        throw new UnsupportedOperationException("Proof photos are not implemented yet");
    }

    static StaffTaskFailure unavailable() {
        return new StaffTaskFailure(503, "EVIDENCE_STORAGE_UNAVAILABLE", "Photo storage is unavailable");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
