package museon_online.astor_butler.api.glasses;

import io.minio.*;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesS3StorageTest {
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("venue", "staff");
    private final MinioClient client = mock(MinioClient.class);
    private final GlassesS3Storage storage = new GlassesS3Storage(client, "unit-private-bucket");

    @Test void photoMetadataIsStoredBesideTheScopedJpegWithoutTaskClaims() throws Exception {
        var context = new GlassesPhotoContext("80d26cf1-5139-4121-a4ca-dfb14aac225c", "BUSINESS_LUNCH_TWO", "FINAL_CHECK", 4);
        when(client.putObject(any(PutObjectArgs.class))).thenAnswer(i -> {
            PutObjectArgs args = i.getArgument(0);
            if (args.object().endsWith("reply.json")) {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args.stream().readAllBytes());
                assertThat(json.path("photoContext").path("sessionId").asText()).isEqualTo(context.sessionId());
                assertThat(json.path("photoContext").path("stageCode").asText()).isEqualTo("FINAL_CHECK");
                assertThat(json.toString()).doesNotContain("taskId", "tableId", "evidenceId");
            }
            return null;
        });
        storage.archive(scope, "ff5a8c58-bb60-43f4-b542-1e26c8b96581", "image", new byte[]{1}, "Ответ", context);
        verify(client, times(2)).putObject(any());
    }

    @Test void boundedDocumentsStayTenantScopedAndAreCached() throws Exception {
        String reference = "Учебный контекст, не меню ресторана.";
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(i -> {
            GetObjectArgs args = i.getArgument(0);
            return new GetObjectResponse(Headers.of(), args.bucket(), "ru-central1", args.object(),
                    new ByteArrayInputStream(reference.getBytes(StandardCharsets.UTF_8)));
        });
        assertThat(storage.context(scope)).isEqualTo(reference);
        assertThat(storage.context(scope)).isEqualTo(reference);
        verify(client, times(1)).getObject(argThat(a -> a.object().equals(GlassesS3Storage.documentKey(scope))));
        assertThat(storage.documentsReady()).isTrue();
        storage.context(new GlassesAccess.Scope("other-venue", "staff"));
        verify(client).getObject(argThat(a -> a.object().equals(GlassesS3Storage.documentKey(new GlassesAccess.Scope("other-venue", "staff")))));
    }

    @Test void oversizedOrInvalidUtf8DocumentsFailWithoutDiagnosticLeak() throws Exception {
        for (byte[] bytes : new byte[][]{new byte[32769], {(byte)0xc3, (byte)0x28}}) {
            when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(Headers.of(), "b", "r", "k", new ByteArrayInputStream(bytes)));
            assertThatThrownBy(() -> storage.context(scope)).satisfies(e -> {
                assertThat(((GlassesFailure)e).code).isEqualTo("KNOWLEDGE_UNAVAILABLE");
            });
            assertThat(storage.documentsReady()).isFalse();
        }
    }

    @Test void archiveUsesPrivateScopeAndCanonicalRequestKeysOnly() throws Exception {
        String id = "FF5A8C58-BB60-43F4-B542-1E26C8B96581";
        storage.archive(scope, id, "audio", new byte[]{1}, "fixture reply");
        String prefix = "materials/" + GlassesS3Storage.scopeKey(scope) + "/" + id.toLowerCase() + "/";
        verify(client).putObject(argThat(a -> a.object().equals(prefix+"input.m4a") && a.bucket().equals("unit-private-bucket")));
        verify(client).putObject(argThat(a -> a.object().equals(prefix+"reply.json")));
        verifyNoMoreInteractions(client);
        assertThat(storage.mediaReady()).isTrue();
        assertThat(GlassesS3Storage.scopeKey(scope)).isNotEqualTo(GlassesS3Storage.scopeKey(new GlassesAccess.Scope("venue", "other")));
    }

    @Test void storageFailureIsExplicitAndSanitized() throws Exception {
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(new IllegalStateException("private S3 credentials diagnostic"));
        assertThatThrownBy(() -> storage.archive(scope, "ff5a8c58-bb60-43f4-b542-1e26c8b96581", "text", new byte[0], "reply"))
                .hasMessageNotContaining("credentials").satisfies(e -> assertThat(((GlassesFailure)e).code).isEqualTo("STORAGE_UNAVAILABLE"));
        assertThat(storage.mediaReady()).isFalse();
    }

    @Test void journalAndMaterialReadsAreScopedBoundedAndQuietWhenTheArchiveRefuses() throws Exception {
        String session = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
        String request = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(i -> {
            GetObjectArgs args = i.getArgument(0);
            if (args.object().equals(GlassesS3Storage.journalKey(scope, session)))
                return new GetObjectResponse(Headers.of(), args.bucket(), "ru-central1", args.object(), new ByteArrayInputStream("{\"sessionId\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
            if (args.object().endsWith("/" + request + "/input.jpg"))
                return new GetObjectResponse(Headers.of(), args.bucket(), "ru-central1", args.object(), new ByteArrayInputStream(new byte[]{1, 2, 3}));
            throw new IllegalStateException("AccessDenied private diagnostic");
        });
        assertThat(new String(storage.readJournal(scope, session), StandardCharsets.UTF_8)).contains("sessionId");
        assertThat(storage.material(scope, request, "input.jpg")).containsExactly(1, 2, 3);
        assertThat(storage.material(scope, request, "reply.json")).isNull();
        assertThat(storage.material(scope, "ff5a8c58-bb60-43f4-b542-1e26c8b96582", "input.jpg")).isNull();
        assertThat(storage.readJournal(scope, "22222222-2222-4222-8222-222222222222")).isNull();
        assertThat(GlassesS3Storage.journalKey(scope, session)).isEqualTo("materials/" + GlassesS3Storage.scopeKey(scope) + "/sessions/" + session + "/journal.json");
        assertThat(GlassesS3Storage.disabled().readJournal(scope, session)).isNull();
    }

    @Test void journalWritesReportFailureInsteadOfThrowing() throws Exception {
        String session = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
        assertThat(storage.writeJournal(scope, session, "{}".getBytes(StandardCharsets.UTF_8))).isTrue();
        verify(client).putObject(argThat(a -> a.object().equals(GlassesS3Storage.journalKey(scope, session))));
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(new IllegalStateException("private S3 credentials diagnostic"));
        assertThat(storage.writeJournal(scope, session, "{}".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(storage.writeJournal(scope, session, new byte[GlassesS3Storage.JOURNAL_LIMIT + 1])).isFalse();
        assertThat(GlassesS3Storage.disabled().writeJournal(scope, session, new byte[1])).isFalse();
    }
}
