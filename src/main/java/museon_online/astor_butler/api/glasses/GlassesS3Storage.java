package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Private, server-scoped storage. No public URLs, bucket creation, listing or diagnostic logging. */
@Component
public class GlassesS3Storage {
    private static final int DOCUMENT_LIMIT = 32768;
    private final MinioClient client;
    private final String bucket;
    private volatile Document document;
    private volatile Instant mediaReadyUntil = Instant.MIN;

    @Autowired
    public GlassesS3Storage(@Value("${astor.glasses.s3-enabled:false}") boolean enabled,
                            @Value("${astor.glasses.s3-endpoint:https://storage.yandexcloud.net}") String endpoint,
                            @Value("${astor.glasses.s3-bucket:}") String bucket,
                            @Value("${astor.glasses.s3-access-key:}") String accessKey,
                            @Value("${astor.glasses.s3-secret-key:}") String secretKey) {
        this.bucket = bucket;
        if (!enabled) { this.client = null; return; }
        if (!endpoint.equals("https://storage.yandexcloud.net") || bucket.isBlank()
                || accessKey.isBlank() || secretKey.isBlank()) throw new IllegalStateException("Private storage is not configured");
        this.client = MinioClient.builder().endpoint(endpoint).region("ru-central1")
                .credentials(accessKey, secretKey)
                .httpClient(new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(8)).callTimeout(Duration.ofSeconds(8))
                        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()).build();
    }

    GlassesS3Storage(MinioClient client, String bucket) { this.client = client; this.bucket = bucket; }
    static GlassesS3Storage disabled() { return new GlassesS3Storage(null, ""); }

    boolean mediaReady() { return client != null && Instant.now().isBefore(mediaReadyUntil); }
    boolean documentsReady() { return document != null && Instant.now().isBefore(document.expiresAt); }

    String context(GlassesAccess.Scope scope) {
        if (client == null) return "";
        String key = documentKey(scope);
        Document cached = document;
        if (cached != null && cached.key.equals(key) && Instant.now().isBefore(cached.expiresAt)) return cached.text;
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes = input.readNBytes(DOCUMENT_LIMIT + 1);
            if (bytes.length == 0 || bytes.length > DOCUMENT_LIMIT) throw new IllegalStateException();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().trim();
            if (text.isBlank()) throw new IllegalStateException();
            document = new Document(key, text, Instant.now().plusSeconds(60));
            return text;
        } catch (Exception e) {
            document = null;
            throw new GlassesFailure(503, "KNOWLEDGE_UNAVAILABLE", "Assistant reference documents unavailable");
        }
    }

    void archive(GlassesAccess.Scope scope, String requestId, String kind, byte[] media, String answer) {
        archive(scope, requestId, kind, media, answer, null);
    }

    boolean enabled() { return client != null; }

    void archive(GlassesAccess.Scope scope, String requestId, String kind, byte[] media, String answer,
                 GlassesPhotoContext photoContext) {
        if (client == null) return;
        String prefix = "materials/" + scopeKey(scope) + "/" + UUID.fromString(requestId) + "/";
        try {
            if (media.length > 0) put(prefix + (kind.equals("audio") ? "input.m4a" : "input.jpg"), media,
                    kind.equals("audio") ? "audio/mp4" : "image/jpeg");
            var record = new java.util.LinkedHashMap<String, Object>();
            record.put("requestId", UUID.fromString(requestId).toString());
            record.put("kind", kind);
            record.put("text", answer);
            record.put("createdAt", Instant.now().toString());
            if (photoContext != null) record.put("photoContext", photoContext);
            byte[] reply = new ObjectMapper().writeValueAsBytes(record);
            put(prefix + "reply.json", reply, "application/json");
            mediaReadyUntil = Instant.now().plusSeconds(300);
        } catch (Exception e) {
            mediaReadyUntil = Instant.MIN;
            throw new GlassesFailure(503, "STORAGE_UNAVAILABLE", "Private material storage unavailable");
        }
    }

    private void put(String key, byte[] bytes, String contentType) throws Exception {
        try (var input = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(input, bytes.length, -1).contentType(contentType).build());
        }
    }

    static String documentKey(GlassesAccess.Scope scope) {
        return "documents/" + GlassesReplyCache.digest(scope.tenant().getBytes(StandardCharsets.UTF_8)) + "/context.txt";
    }

    static String scopeKey(GlassesAccess.Scope scope) {
        return GlassesReplyCache.digest(scope.tenant().getBytes(StandardCharsets.UTF_8), scope.staff().getBytes(StandardCharsets.UTF_8));
    }

    private record Document(String key, String text, Instant expiresAt) { }
}
