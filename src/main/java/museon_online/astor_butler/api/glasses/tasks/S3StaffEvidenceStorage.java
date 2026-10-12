package museon_online.astor_butler.api.glasses.tasks;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * A private bucket behind the MinIO client the project already uses for object storage. It only puts and
 * gets single objects: no public links, no bucket creation, no listing, no delete. Expiry is the bucket's
 * lifecycle rule. A failure is reported as one sanitized 503, without the bucket, the key or the cause.
 */
final class S3StaffEvidenceStorage implements StaffEvidenceStorage {
    private static final Logger log = LoggerFactory.getLogger(S3StaffEvidenceStorage.class);
    private final MinioClient client;
    private final String bucket;

    S3StaffEvidenceStorage(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        var builder = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey)
                .httpClient(new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(20)).writeTimeout(Duration.ofSeconds(20))
                        .callTimeout(Duration.ofSeconds(30)).retryOnConnectionFailure(false)
                        .followRedirects(false).followSslRedirects(false).build());
        // With a region the client signs at once; without one it asks the storage where the bucket is.
        if (region != null && !region.isBlank()) builder.region(region.strip());
        this.client = builder.build();
        this.bucket = bucket;
    }

    S3StaffEvidenceStorage(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override public void put(String key, byte[] bytes, String contentType) {
        try (var input = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(input, bytes.length, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw failed("put", e);
        }
    }

    @Override public Optional<byte[]> get(String key, int limit) {
        byte[] bytes;
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            bytes = input.readNBytes(limit + 1);
        } catch (ErrorResponseException e) {
            // Only "there is no such object" is an answer; a refusal or an outage is not "the photo is gone".
            if (e.response() != null && e.response().code() == 404 && e.errorResponse() != null
                    && "NoSuchKey".equals(e.errorResponse().code())) return Optional.empty();
            throw failed("get", e);
        } catch (Exception e) {
            throw failed("get", e);
        }
        if (bytes.length == 0 || bytes.length > limit) throw StaffEvidenceStorage.unavailable();
        return Optional.of(bytes);
    }

    /** The log gets the kind of failure only: a client message may quote the bucket, the key or the endpoint. */
    private static StaffTaskFailure failed(String operation, Exception e) {
        log.warn("Staff evidence storage {} failed: {}", operation,
                e instanceof ErrorResponseException refused && refused.errorResponse() != null
                        ? refused.errorResponse().code() : e.getClass().getSimpleName());
        return StaffEvidenceStorage.unavailable();
    }
}
