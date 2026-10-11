package museon_online.astor_butler.api.glasses.tasks;

import io.minio.MinioClient;

import java.util.Optional;

/** A private bucket behind the MinIO client the project already uses. Signatures only: nothing is stored yet. */
final class S3StaffEvidenceStorage implements StaffEvidenceStorage {

    S3StaffEvidenceStorage(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        this(null, bucket);
    }

    S3StaffEvidenceStorage(MinioClient client, String bucket) {
    }

    @Override public void put(String key, byte[] bytes, String contentType) {
        throw new UnsupportedOperationException("Proof photos are not implemented yet");
    }

    @Override public Optional<byte[]> get(String key, int limit) {
        throw new UnsupportedOperationException("Proof photos are not implemented yet");
    }
}
