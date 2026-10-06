package museon_online.astor_butler.api.glasses;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesMediaStorageTest {
    private static final String FILE = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private static final String SESSION = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("venue", "staff");
    private final MinioClient client = mock(MinioClient.class);
    private final GlassesS3Storage storage = enabledStorage();
    private final byte[] bytes = "fixture".getBytes(StandardCharsets.UTF_8);
    private final String sha = GlassesMediaController.sha256(bytes);

    private GlassesS3Storage enabledStorage() {
        var result = new GlassesS3Storage(client, "private-test-bucket");
        result.configureMediaArchive(true);
        return result;
    }

    private ErrorResponseException error(int status, String code) {
        var response = new Response.Builder().request(new Request.Builder().url("https://storage.example.test/").build())
                .protocol(Protocol.HTTP_1_1).code(status).message(code).build();
        return new ErrorResponseException(new ErrorResponse(code, "private diagnostic", "b", "k", "r", "id", "host"), response, "");
    }

    @Test void durableReceiptSurvivesNewStorageInstanceAndChangedContextConflicts() throws Exception {
        var objects = new HashMap<String, byte[]>();
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(i -> {
            GetObjectArgs a = i.getArgument(0);
            assertThat(a.object()).endsWith("archive-receipt.json");
            if (!objects.containsKey(a.object())) throw error(404, "NoSuchKey");
            return new GetObjectResponse(Headers.of(), a.bucket(), "r", a.object(), new ByteArrayInputStream(objects.get(a.object())));
        });
        when(client.putObject(any(PutObjectArgs.class))).thenAnswer(i -> {
            PutObjectArgs a = i.getArgument(0); objects.put(a.object(), a.stream().readAllBytes()); return null;
        });
        var first = storage.archiveFile(scope, FILE, SESSION, sha, "audio/mp4", bytes);
        assertThat(first.archived()).isTrue();
        String prefix = "materials/" + GlassesS3Storage.scopeKey(scope) + "/" + FILE + "/";
        assertThat(objects).containsKeys(prefix + "archive.m4a", prefix + "archive-receipt.json");
        assertThat(enabledStorage().archiveFile(scope, FILE, SESSION, sha, "audio/mp4", bytes)).isEqualTo(first);
        verify(client, times(2)).putObject(any());
        assertThatThrownBy(() -> storage.archiveFile(scope, FILE, "a439e074-03c6-4413-95fa-375ec04725ef", sha, "audio/mp4", bytes))
                .satisfies(e -> assertThat(((GlassesFailure)e).status).isEqualTo(409));
        assertThatThrownBy(() -> storage.archiveFile(scope, FILE, SESSION, sha, "video/mp4", bytes))
                .satisfies(e -> assertThat(((GlassesFailure)e).code).isEqualTo("FILE_ID_CONFLICT"));
        assertThatThrownBy(() -> storage.archiveFile(scope, FILE, SESSION, "0".repeat(64), "audio/mp4", bytes))
                .satisfies(e -> assertThat(((GlassesFailure)e).status).isEqualTo(409));
        verify(client, times(2)).putObject(any());
    }

    @Test void unreadableReceiptFailsClosedWithoutOverwritingMedia() throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(error(403, "AccessDenied"));
        assertThatThrownBy(() -> storage.archiveFile(scope, FILE, SESSION, sha, "image/jpeg", bytes))
                .hasMessageNotContaining("diagnostic").satisfies(e -> assertThat(((GlassesFailure)e).code).isEqualTo("STORAGE_UNAVAILABLE"));
        verify(client, never()).putObject(any());
    }

    @Test void invalidOrOversizedReceiptFailsClosed() throws Exception {
        for (byte[] content : new byte[][]{ "{}".getBytes(), new byte[4097] }) {
            when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(Headers.of(), "b", "r", "k", new ByteArrayInputStream(content)));
            assertThatThrownBy(() -> storage.archiveFile(scope, FILE, SESSION, sha, "image/jpeg", bytes))
                    .satisfies(e -> assertThat(((GlassesFailure)e).status).isEqualTo(503));
        }
        verify(client, never()).putObject(any());
    }

    @Test void mediaWrittenWithoutSuccessfulCommitNeverAcknowledgesArchive() throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(error(404, "NoSuchKey"));
        when(client.putObject(any(PutObjectArgs.class))).thenAnswer(i -> {
            PutObjectArgs a = i.getArgument(0);
            if (a.object().endsWith("archive-receipt.json")) throw new IllegalStateException("private diagnostic");
            return null;
        });
        assertThatThrownBy(() -> storage.archiveFile(scope, FILE, SESSION, sha, "image/jpeg", bytes))
                .satisfies(e -> assertThat(((GlassesFailure)e).status).isEqualTo(503));
        verify(client, times(2)).putObject(any());
    }

    @Test void archiveSwitchDefaultsOffAndCannotEnableMissingStorage() {
        assertThat(new GlassesS3Storage(client, "b").archiveCapabilities().enabled()).isFalse();
        var disabled = GlassesS3Storage.disabled(); disabled.configureMediaArchive(true);
        assertThat(disabled.archiveCapabilities().enabled()).isFalse();
    }
}
