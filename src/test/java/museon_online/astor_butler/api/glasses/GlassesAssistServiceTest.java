package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesAssistServiceTest {
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("unit-venue", "unit-staff");

    @Test void s3DocumentsGroundAnswerAndStorageRetryDoesNotRepeatModelCall() {
        var gateway = mock(ModelGateway.class);
        var storage = mock(GlassesS3Storage.class);
        var voice = mock(GlassesVoice.class);
        when(storage.context(scope)).thenReturn("Учебная подсказка из S3.");
        when(gateway.generateText(any())).thenReturn(ModelTextResponse.text("Ответ.", "test", "test", Duration.ZERO));
        String id = UUID.randomUUID().toString();
        doThrow(new GlassesFailure(503, "STORAGE_UNAVAILABLE", "Private material storage unavailable"))
                .doNothing().when(storage).archive(eq(scope), eq(id), eq("text"), any(), eq("Ответ."));
        var service = new GlassesAssistService(gateway, voice, storage, true, 1000);
        try {
            assertThatThrownBy(() -> service.assist(scope, id, "вопрос")).satisfies(e ->
                    assertThat(((GlassesFailure)e).code).isEqualTo("STORAGE_UNAVAILABLE"));
            assertThat(whenSlotAvailable(() -> service.assist(scope, id, "вопрос"))).isEqualTo("Ответ.");
            verify(gateway, times(1)).generateText(argThat(r -> r.prompt().contains("Учебная подсказка из S3.")));
            verify(storage, times(2)).archive(eq(scope), eq(id), eq("text"), any(), eq("Ответ."));
        } finally { service.close(); }
    }

    @Test void noSpeechDoesNotCallModelOrArchiveAndPreservesPreviousTextReadiness() {
        var gateway = mock(ModelGateway.class);
        var storage = mock(GlassesS3Storage.class);
        var voice = mock(GlassesVoice.class);
        when(storage.context(scope)).thenReturn("");
        when(gateway.generateText(any())).thenReturn(ModelTextResponse.text("Ответ.", "test", "test", Duration.ZERO));
        when(voice.transcribe(any())).thenThrow(new GlassesFailure(400, "NO_SPEECH", "Record again"));
        var service = new GlassesAssistService(gateway, voice, storage, true, 1000);
        try {
            service.assist("first question");
            assertThat(service.capabilities().text()).isTrue();
            String id = UUID.randomUUID().toString();
            assertThatThrownBy(() -> whenSlotAvailable(() -> service.assistAudio(scope, id, "", new byte[]{1})))
                    .satisfies(e -> assertThat(((GlassesFailure)e).code).isEqualTo("NO_SPEECH"));
            assertThat(service.capabilities().text()).isTrue();
            verify(gateway, times(1)).generateText(any());
            verify(storage, never()).archive(any(), any(), any(), any(), any());
        } finally { service.close(); }
    }

    @Test void disabledTextNeverCallsProvider() {
        var gateway = mock(ModelGateway.class);
        try (var ignored = new ServiceCloser(new GlassesAssistService(gateway, false, 50))) {
            assertThatThrownBy(() -> ignored.service.assist("question")).isInstanceOf(GlassesFailure.class);
            verifyNoInteractions(gateway);
        }
    }

    @Test void fallbackIsNotSuccess() {
        var gateway = mock(ModelGateway.class);
        when(gateway.generateText(any())).thenReturn(new ModelTextResponse("fallback", "test", "test", null,
                Duration.ZERO, true, Map.of()));
        try (var closer = new ServiceCloser(new GlassesAssistService(gateway, true, 1000))) {
            assertThatThrownBy(() -> closer.service.assist("question")).isInstanceOf(GlassesFailure.class);
            assertThat(closer.service.capabilities().text()).isFalse();
        }
    }

    @Test void timeoutDoesNotOpenAnotherSlotWhileProviderIgnoresInterruption() throws Exception {
        var gateway = mock(ModelGateway.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.generateText(any())).thenAnswer(invocation -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* emulate an uncooperative network client */ }
            }
            return ModelTextResponse.text("late", "test", "test", Duration.ZERO);
        });
        var service = new GlassesAssistService(gateway, true, 100);
        try {
            assertThatThrownBy(() -> service.assist("first")).satisfies(e -> {
                assertThat(((GlassesFailure) e).status).isEqualTo(503);
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.assist("second")).satisfies(e -> {
                assertThat(((GlassesFailure) e).status).isEqualTo(429);
            });
            assertThat(service.capabilities().text()).isFalse();
            verify(gateway, times(1)).generateText(any());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test void lateModelResultAfterTimeoutIsNotArchivedOrReused() throws Exception {
        var gateway = mock(ModelGateway.class);
        var storage = mock(GlassesS3Storage.class);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(storage.context(scope)).thenReturn("");
        when(gateway.generateText(any())).thenAnswer(invocation -> {
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { /* emulate a provider that ignores cancellation */ }
            }
            finished.countDown();
            return ModelTextResponse.text("late", "test", "test", Duration.ZERO);
        });
        String id = UUID.randomUUID().toString();
        var service = new GlassesAssistService(gateway, mock(GlassesVoice.class), storage, true, 100);
        try {
            assertThatThrownBy(() -> service.assist(scope, id, "question"))
                    .satisfies(e -> assertThat(((GlassesFailure)e).status).isEqualTo(503));
            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(whenSlotAvailable(() -> service.assist(scope, id, "question"))).isEqualTo("late");
            verify(gateway, times(2)).generateText(any());
            verify(storage, times(1)).archive(eq(scope), eq(id), eq("text"), any(), eq("late"));
        } finally { release.countDown(); service.close(); }
    }

    // A completed Future may wake its caller just before the SynchronousQueue worker becomes idle.
    // BUSY is allowed during that handoff; do not confuse it with the failure under test.
    private static String whenSlotAvailable(Supplier<String> call) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (true) {
            try { return call.get(); }
            catch (GlassesFailure e) {
                if (!e.code.equals("BUSY") || System.nanoTime() >= deadline) throw e;
                try { Thread.sleep(5); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    private record ServiceCloser(GlassesAssistService service) implements AutoCloseable {
        @Override public void close() { service.close(); }
    }
}
