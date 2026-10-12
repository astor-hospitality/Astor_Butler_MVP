package museon_online.astor_butler.api.glasses.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Set;

import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.heic;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How one photo is read from a request: the declared type first, the body never further than the limit. */
class StaffEvidenceUploadTest {
    private static final StaffEvidence.Limits LIMITS = new StaffEvidence.Limits(1000, Set.of("image/jpeg", "image/heic"));

    private static MockHttpServletRequest request(String type, byte[] body, boolean lengthDeclared) {
        var request = new MockHttpServletRequest("POST", "/api/staff/tasks/task-1/evidence") {
            @Override public long getContentLengthLong() { return lengthDeclared ? super.getContentLengthLong() : -1; }
            @Override public int getContentLength() { return lengthDeclared ? super.getContentLength() : -1; }
        };
        request.setContentType(type);
        request.setContent(body);
        request.setParameter("eventId", "photo-1");
        request.setParameter("stageCode", "setting");
        return request;
    }

    private static void refused(MockHttpServletRequest request, int status, String code) {
        assertThatThrownBy(() -> StaffEvidenceUpload.read(request, LIMITS)).isInstanceOfSatisfying(StaffTaskFailure.class, failure -> {
            assertThat(failure.status).isEqualTo(status);
            assertThat(failure.code).isEqualTo(code);
        });
    }

    @Test void aPhotoIsReadWithItsEventStageAndCaptureTime() throws Exception {
        var request = request("IMAGE/JPEG; quality=high", jpeg(1000), true);
        request.setParameter("capturedAt", " 2026-10-11T08:59:30Z ");
        var upload = StaffEvidenceUpload.read(request, LIMITS);
        assertThat(upload.eventId()).isEqualTo("photo-1");
        assertThat(upload.stageCode()).isEqualTo("setting");
        assertThat(upload.capturedAt()).isEqualTo(Instant.parse("2026-10-11T08:59:30Z"));
        assertThat(upload.contentType()).isEqualTo("image/jpeg");
        assertThat(upload.bytes()).isEqualTo(jpeg(1000));
        assertThat(StaffEvidenceUpload.read(request("image/heic", heic(), true), LIMITS).capturedAt()).isNull();
    }

    @Test void aBodyWithoutADeclaredLengthIsStillReadNoFurtherThanTheLimit() throws Exception {
        assertThat(StaffEvidenceUpload.read(request("image/jpeg", jpeg(1000), false), LIMITS).bytes()).hasSize(1000);
        refused(request("image/jpeg", jpeg(1001), false), 413, "PAYLOAD_TOO_LARGE");
        refused(request("image/jpeg", jpeg(1001), true), 413, "PAYLOAD_TOO_LARGE");
        refused(request("image/jpeg", new byte[0], false), 400, "MALFORMED_REQUEST");
        refused(request("image/jpeg", new byte[0], true), 400, "MALFORMED_REQUEST");
    }

    @Test void theTypeIsSettledBeforeAnyParameterIsAskedFor() {
        // A form body would be parsed for parameters by the container; it is refused by its type first.
        var form = request("application/x-www-form-urlencoded", "eventId=x&stageCode=y".getBytes(), true);
        form.removeAllParameters();
        refused(form, 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(request(null, jpeg(100), true), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(request("not a type", jpeg(100), true), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(request("image/png", jpeg(100), true), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(request("image/heic", jpeg(100), true), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test void eventStageAndCaptureTimeAreChecked() {
        var noEvent = request("image/jpeg", jpeg(100), true);
        noEvent.removeParameter("eventId");
        refused(noEvent, 400, "MALFORMED_REQUEST");
        var longEvent = request("image/jpeg", jpeg(100), true);
        longEvent.setParameter("eventId", "e".repeat(65));
        refused(longEvent, 400, "MALFORMED_REQUEST");
        var noStage = request("image/jpeg", jpeg(100), true);
        noStage.setParameter("stageCode", " ");
        refused(noStage, 400, "MALFORMED_REQUEST");
        var badTime = request("image/jpeg", jpeg(100), true);
        badTime.setParameter("capturedAt", "11.10.2026 12:00");
        refused(badTime, 400, "MALFORMED_REQUEST");
    }
}
