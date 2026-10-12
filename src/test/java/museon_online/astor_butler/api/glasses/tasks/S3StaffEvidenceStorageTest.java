package museon_online.astor_butler.api.glasses.tasks;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;

import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** The bucket adapter with the client mocked, the way the glasses storage is tested: no real bucket is needed. */
class S3StaffEvidenceStorageTest {
    private static final String BUCKET = "unit-private-evidence";
    private final MinioClient client = mock(MinioClient.class);
    private final StaffEvidenceStorage storage = new S3StaffEvidenceStorage(client, BUCKET);
    private final String key = StaffEvidenceStorage.key("AERIS", "task-1", "photo-1", "image/jpeg");

    private static ErrorResponseException error(int status, String code) {
        var response = new Response.Builder().request(new Request.Builder().url("https://storage.example.test/").build())
                .protocol(Protocol.HTTP_1_1).code(status).message(code).build();
        return new ErrorResponseException(new ErrorResponse(code, "private diagnostic", "b", "k", "r", "id", "host"), response, "");
    }

    private void stored(byte[] bytes) throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(call -> {
            GetObjectArgs args = call.getArgument(0);
            return new GetObjectResponse(Headers.of(), args.bucket(), "r", args.object(), new ByteArrayInputStream(bytes));
        });
    }

    private static void unavailable(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(StaffTaskFailure.class, refused -> {
            assertThat(refused.status).isEqualTo(503);
            assertThat(refused.code).isEqualTo("EVIDENCE_STORAGE_UNAVAILABLE");
        }).hasMessageNotContaining("diagnostic").hasMessageNotContaining(BUCKET).hasMessageNotContaining("staff-evidence");
    }

    @Test void theKeyHasOnePrefixForTheVenueAndOneForTheTaskAndNoVenueName() {
        String venue = StaffEvidenceStorage.sha256("AERIS".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(StaffEvidenceStorage.taskPrefix("AERIS", "task-1")).isEqualTo("staff-evidence/" + venue + "/task-1/");
        assertThat(key).isEqualTo("staff-evidence/" + venue + "/task-1/photo-1.jpg").doesNotContain("AERIS");
        assertThat(StaffEvidenceStorage.taskPrefix("OTHER", "task-1")).isNotEqualTo(StaffEvidenceStorage.taskPrefix("AERIS", "task-1"));
        assertThat(StaffEvidenceStorage.taskPrefix("AERIS", "task-2")).startsWith("staff-evidence/" + venue + "/")
                .isNotEqualTo(StaffEvidenceStorage.taskPrefix("AERIS", "task-1"));
        // An id that is not one the server makes can never walk out of its prefix.
        assertThat(StaffEvidenceStorage.key("AERIS", "../other-venue", "a/b", "image/png"))
                .matches("staff-evidence/" + venue + "/[0-9a-f]{64}/[0-9a-f]{64}\\.png");
        assertThat(StaffEvidenceStorage.key("Кафе на углу / №1", "task-1", "photo-1", "image/heic"))
                .matches("staff-evidence/[0-9a-f]{64}/task-1/photo-1\\.heic");
    }

    @Test void aPhotoIsPutIntoThePrivateBucketUnderItsKeyAndNothingElseIsAsked() throws Exception {
        byte[] photo = jpeg(300);
        storage.put(key, photo, "image/jpeg");

        var sent = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(sent.capture());
        assertThat(sent.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(sent.getValue().object()).isEqualTo(key);
        assertThat(sent.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(sent.getValue().stream().readAllBytes()).isEqualTo(photo);

        stored(photo);
        assertThat(storage.get(key, 300)).hasValue(photo);
        verify(client).getObject(any(GetObjectArgs.class));
        // No bucket check or creation, no listing, no delete, no public link.
        verifyNoMoreInteractions(client);
    }

    @Test void aMissingObjectIsAnAnswerAndEveryOtherFailureIsOneSanitized503() throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(error(404, "NoSuchKey"));
        assertThat(storage.get(key, 300)).isEmpty();

        for (Exception failure : new Exception[]{error(403, "AccessDenied"), error(404, "NoSuchBucket"),
                new java.io.IOException("private diagnostic: connection reset"), new IllegalStateException("private diagnostic")}) {
            var broken = mock(MinioClient.class);
            when(broken.getObject(any(GetObjectArgs.class))).thenThrow(failure);
            when(broken.putObject(any(PutObjectArgs.class))).thenThrow(failure);
            var refusing = new S3StaffEvidenceStorage(broken, BUCKET);
            assertThatThrownBy(() -> refusing.get(key, 300)).satisfies(S3StaffEvidenceStorageTest::unavailable);
            assertThatThrownBy(() -> refusing.put(key, jpeg(300), "image/jpeg")).satisfies(S3StaffEvidenceStorageTest::unavailable);
        }
    }

    @Test void anObjectLargerThanItsRecordOrEmptyIsNotHandedOut() throws Exception {
        stored(jpeg(301));
        assertThatThrownBy(() -> storage.get(key, 300)).satisfies(S3StaffEvidenceStorageTest::unavailable);
        stored(new byte[0]);
        assertThatThrownBy(() -> storage.get(key, 300)).satisfies(S3StaffEvidenceStorageTest::unavailable);
    }
}
