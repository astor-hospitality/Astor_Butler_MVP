package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GlassesReportControllerTest {
    private static final String SESSION = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
    private static final String PHOTO = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private static final String PASSWORD = "unit-report-password-not-real";
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("test-venue", "test-staff");
    private final GlassesAccess access = new GlassesAccess("", "test-venue", "test-staff", "");
    private final GlassesS3Storage storage = mock(GlassesS3Storage.class);
    private final GlassesSessionJournal journal = new GlassesSessionJournal(storage, Clock.systemUTC(), false);
    private final GlassesReportController controller =
            new GlassesReportController(new GlassesReportAuth(access, PASSWORD), journal, storage);

    private MockHttpServletRequest get(String path, String user, String password) {
        var request = new MockHttpServletRequest("GET", path);
        if (user != null) {
            request.addHeader("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return request;
    }

    private void walk() {
        journal.recordEvents(scope, "11111111-1111-4111-8111-111111111111", SESSION, "BUSINESS_LUNCH_TWO", List.of(
                new GlassesSessionJournal.ClientEvent("2026-10-07T06:00:00Z", "STEP_STARTED", "PLACE_SETTINGS", null),
                new GlassesSessionJournal.ClientEvent("2026-10-07T06:00:40Z", "CALL_STARTED", null, null),
                new GlassesSessionJournal.ClientEvent("2026-10-07T06:01:10Z", "CALL_ENDED", null, "гость спрашивал адрес"),
                new GlassesSessionJournal.ClientEvent("2026-10-07T06:02:00Z", "STEP_STARTED", "FINAL_CHECK", null),
                new GlassesSessionJournal.ClientEvent("2026-10-07T06:03:30Z", "SESSION_FINISHED", null, null)));
        journal.recordAssist(scope, new GlassesPhotoContext(SESSION, "BUSINESS_LUNCH_TWO", "PLACE_SETTINGS", 2), PHOTO, "image",
                "Видны два комплекта <приборов> & бокалы.", null, 1800, true);
        journal.recordAssist(scope, new GlassesPhotoContext(SESSION, "BUSINESS_LUNCH_TWO", "FINAL_CHECK", 7),
                "ff5a8c58-bb60-43f4-b542-1e26c8b96582", "audio", null, "TEXT_UNAVAILABLE", 45000, false);
    }

    @Test void theReportIsPlainHtmlWithStepsCallsAndEscapedAnswers() {
        walk();
        var result = controller.report(get("/api/glasses/sessions/" + SESSION + "/report", "astor", PASSWORD), SESSION);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(result.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
        String html = (String) result.getBody();
        assertThat(html).doesNotContain("<script");
        assertThat(html).contains("&lt;приборов&gt; &amp; бокалы").doesNotContain("<приборов>");
        assertThat(html).contains("PLACE_SETTINGS").contains("FINAL_CHECK").contains("звонок начался").contains("гость спрашивал адрес");
        assertThat(html).contains("TEXT_UNAVAILABLE").contains("1800 мс").contains("в архиве");
        assertThat(html).contains("photo/" + PHOTO).doesNotContain("photo/ff5a8c58-bb60-43f4-b542-1e26c8b96582");
        assertThat(html).doesNotContain("taskId").doesNotContain("Bearer");
    }

    @Test void sessionsListIsScopedAndSummarised() {
        walk();
        var result = controller.sessions(get("/api/glasses/sessions", "astor", PASSWORD));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked") var list = (List<GlassesSessionJournal.Summary>) result.getBody();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).requests()).isEqualTo(2);
        assertThat(list.get(0).errors()).isEqualTo(1);
        assertThat(list.get(0).clientEvents()).isEqualTo(5);
    }

    @Test void requiresTheServerPasswordNotTheMobileBearer() {
        walk();
        for (var request : List.of(get("/api/glasses/sessions", null, null), get("/api/glasses/sessions", "astor", "wrong"),
                get("/api/glasses/sessions", "someone", PASSWORD))) {
            var result = controller.sessions(request);
            assertThat(result.getStatusCode().value()).isEqualTo(401);
            assertThat(result.getHeaders().getFirst("WWW-Authenticate")).startsWith("Basic");
        }
        var bearer = new MockHttpServletRequest("GET", "/api/glasses/sessions");
        bearer.addHeader("Authorization", "Bearer " + PASSWORD);
        assertThat(controller.sessions(bearer).getStatusCode().value()).isEqualTo(401);
        var unconfigured = new GlassesReportController(new GlassesReportAuth(access, ""), journal, storage);
        var result = unconfigured.sessions(get("/api/glasses/sessions", "astor", ""));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(((GlassesController.ErrorResponse) result.getBody()).error()).containsEntry("code", "REPORT_UNAVAILABLE");
    }

    @Test void photoStreamsOnlyJournaledJpegsAndSaysNotAvailableWhenTheArchiveRefuses() {
        walk();
        when(storage.material(eq(scope), eq(PHOTO), eq("input.jpg"))).thenReturn(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        var ok = controller.photo(get("/x", "astor", PASSWORD), SESSION, PHOTO);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");

        when(storage.material(any(), any(), any())).thenReturn(null);
        var refused = controller.photo(get("/x", "astor", PASSWORD), SESSION, PHOTO);
        assertThat(refused.getStatusCode().value()).isEqualTo(404);
        assertThat(((GlassesController.ErrorResponse) refused.getBody()).error()).containsEntry("code", "PHOTO_NOT_AVAILABLE");

        var unknown = controller.photo(get("/x", "astor", PASSWORD), SESSION, "ff5a8c58-bb60-43f4-b542-1e26c8b96582");
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        verify(storage, never()).material(any(), eq("ff5a8c58-bb60-43f4-b542-1e26c8b96582"), any());

        var malformed = controller.photo(get("/x", "astor", PASSWORD), "nope", PHOTO);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        var missing = controller.report(get("/x", "astor", PASSWORD), "22222222-2222-4222-8222-222222222222");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }
}
