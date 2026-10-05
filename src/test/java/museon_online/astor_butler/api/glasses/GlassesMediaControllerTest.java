package museon_online.astor_butler.api.glasses;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlassesMediaControllerTest {
    private static final String FILE = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private static final String SESSION = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
    private static final byte[] JPEG = {(byte)255, (byte)216, 0, (byte)255, (byte)217};
    private static final String TOKEN = "unit-archive-only";
    private final GlassesS3Storage storage = mock(GlassesS3Storage.class);
    private final GlassesAccess access = new GlassesAccess(GlassesMediaController.sha256(TOKEN.getBytes()),
            "unit-venue", "unit-staff", Instant.now().plusSeconds(300).toString());
    private final GlassesMediaController controller = new GlassesMediaController(access, storage);

    private MockHttpServletRequest request(byte[] bytes) {
        when(storage.archiveCapabilities()).thenReturn(new GlassesS3Storage.ArchiveCapabilities(true, GlassesMediaController.LIMIT));
        var r = new MockHttpServletRequest("POST", "/api/glasses/media");
        r.addHeader("Authorization", "Bearer " + TOKEN);
        r.addHeader("X-Glasses-File-Id", FILE);
        r.addHeader("X-Glasses-Session-Id", SESSION);
        r.addHeader("X-Content-SHA256", GlassesMediaController.sha256(bytes));
        r.setContentType("image/jpeg");
        r.setContent(bytes);
        return r;
    }

    private MockHttpServletRequest unreadable(long length) {
        var r = new MockHttpServletRequest("POST", "/api/glasses/media") {
            @Override public long getContentLengthLong() { return length; }
            @Override public ServletInputStream getInputStream() { throw new AssertionError("Body must not be read"); }
        };
        r.addHeader("Authorization", "Bearer " + TOKEN);
        r.setContentType("image/jpeg");
        return r;
    }

    @Test void rejectsUnauthorizedAndDisabledArchiveBeforeReadingBody() {
        var r = unreadable(5);
        r.removeHeader("Authorization");
        assertThat(controller.archive(r).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(storage);
        r.addHeader("Authorization", "Bearer " + TOKEN);
        when(storage.archiveCapabilities()).thenReturn(new GlassesS3Storage.ArchiveCapabilities(false, GlassesMediaController.LIMIT));
        assertThat(controller.archive(r).getStatusCode().value()).isEqualTo(503);
        verify(storage, never()).archiveFile(any(), any(), any(), any(), any(), any());
    }

    @Test void rejectsMissingAndOversizedLengthsWithoutAllocatingOrReading() {
        when(storage.archiveCapabilities()).thenReturn(new GlassesS3Storage.ArchiveCapabilities(true, GlassesMediaController.LIMIT));
        assertThat(controller.archive(unreadable(-1)).getStatusCode().value()).isEqualTo(411);
        assertThat(controller.archive(unreadable((long) GlassesMediaController.LIMIT + 1)).getStatusCode().value()).isEqualTo(413);
        verify(storage, never()).archiveFile(any(), any(), any(), any(), any(), any());
    }

    @Test void rejectsChangedBytesInvalidSignatureAndClientPaths() {
        var r = request(JPEG);
        r.setContent(new byte[]{1, 2, 3, 4, 5});
        assertThat(controller.archive(r).getBody().toString()).contains("HASH_MISMATCH");
        r = request(new byte[]{1, 2, 3});
        assertThat(controller.archive(r).getStatusCode().value()).isEqualTo(400);
        r = request(JPEG);
        r.removeHeader("X-Glasses-File-Id"); r.addHeader("X-Glasses-File-Id", "../outside");
        assertThat(controller.archive(r).getStatusCode().value()).isEqualTo(400);
        r = request(JPEG);
        r.setContentType(null);
        assertThat(controller.archive(r).getStatusCode().value()).isEqualTo(400);
        verify(storage, never()).archiveFile(any(), any(), any(), any(), any(), any());
    }

    @Test void returnsOnlyDurableReceiptAndUsesServerScope() {
        var receipt = new GlassesS3Storage.ArchiveReceipt(FILE, SESSION, GlassesMediaController.sha256(JPEG), JPEG.length, true);
        when(storage.archiveFile(any(), any(), any(), any(), any(), any())).thenReturn(receipt);
        var response = controller.archive(request(JPEG));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(receipt);
        verify(storage).archiveFile(new GlassesAccess.Scope("unit-venue", "unit-staff"), FILE, SESSION,
                receipt.sha256(), "image/jpeg", JPEG);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test void failedCommitHasNoArchivedReceipt() {
        when(storage.archiveFile(any(), any(), any(), any(), any(), any()))
                .thenThrow(new GlassesFailure(503, "STORAGE_UNAVAILABLE", "Private material storage unavailable"));
        var response = controller.archive(request(JPEG));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString()).doesNotContain("archived=true");
    }

    @Test void concurrentUploadIsRejectedBeforeReadingItsBodyAndSlotIsReleased() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(storage.archiveFile(any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("No release");
            throw new GlassesFailure(503, "STORAGE_UNAVAILABLE", "Private material storage unavailable");
        });
        var r = request(JPEG);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> controller.archive(r));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                var second = unreadable(5);
                second.addHeader("X-Glasses-File-Id", FILE);
                second.addHeader("X-Glasses-Session-Id", SESSION);
                second.addHeader("X-Content-SHA256", GlassesMediaController.sha256(JPEG));
                var response = controller.archive(second);
                assertThat(response.getStatusCode().value()).isEqualTo(429);
                assertThat(response.getBody().toString()).contains("BUSY");
            } finally { release.countDown(); }
            assertThat(first.get(3, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(503);
            assertThat(controller.archive(request(JPEG)).getStatusCode().value()).isEqualTo(503);
            verify(storage, times(2)).archiveFile(any(), any(), any(), any(), any(), any());
        }
    }
}
