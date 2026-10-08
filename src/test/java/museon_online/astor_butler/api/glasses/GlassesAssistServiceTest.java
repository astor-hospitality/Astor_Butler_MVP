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

    @Test void photoArchiveRetryKeepsStepBindingAndChangedStepConflicts() {
        var gateway = mock(ModelGateway.class);
        var storage = mock(GlassesS3Storage.class);
        when(storage.context(scope)).thenReturn("");
        when(gateway.analyzeImage(any())).thenReturn(museon_online.astor_butler.model.ModelVisionResponse
                .vision("Видны приборы.", "test", "test", Duration.ZERO));
        var context = new GlassesPhotoContext(UUID.randomUUID().toString(), "BUSINESS_LUNCH_TWO", "PLACE_SETTINGS", 2);
        String id = UUID.randomUUID().toString();
        doThrow(new GlassesFailure(503, "STORAGE_UNAVAILABLE", "Unavailable")).doNothing()
                .when(storage).archive(eq(scope), eq(id), eq("image"), any(), eq("Видны приборы."), eq(context));
        try (var service = new GlassesAssistService(gateway, mock(GlassesVoice.class), storage, true, 1000)) {
            assertThatThrownBy(() -> service.assistImage(scope, id, "фото", new byte[]{1}, context))
                    .satisfies(e -> assertThat(((GlassesFailure)e).code).isEqualTo("STORAGE_UNAVAILABLE"));
            assertThat(whenSlotAvailable(() -> service.assistImage(scope, id, "фото", new byte[]{1}, context)))
                    .isEqualTo("Видны приборы.");
            var changed = new GlassesPhotoContext(context.sessionId(), context.scenarioCode(), "FINAL_CHECK", 4);
            assertThatThrownBy(() -> whenSlotAvailable(() -> service.assistImage(scope, id, "фото", new byte[]{1}, changed)))
                    .satisfies(e -> assertThat(((GlassesFailure)e).code).isEqualTo("REQUEST_ID_CONFLICT"));
            verify(gateway, times(1)).analyzeImage(argThat(r -> r.prompt().contains("два комплекта")));
            verify(storage, times(2)).archive(eq(scope), eq(id), eq("image"), any(), any(), eq(context));
        }
    }

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

    @Test void aRequestRightAfterAnAnswerIsAdmittedEveryTime() {
        // The slot is returned by the task before the caller wakes up, so an immediate retry — a cached
        // repeat, a changed question, the next photo — is never refused as BUSY. Before, the executor's
        // own rejection decided, and it could still be busy for a few microseconds after an answer.
        var gateway = mock(ModelGateway.class);
        when(gateway.generateText(any())).thenReturn(ModelTextResponse.text("Ответ.", "test", "test", Duration.ZERO));
        String id = UUID.randomUUID().toString();
        try (var closer = new ServiceCloser(new GlassesAssistService(gateway, true, 1000))) {
            for (int i = 0; i < 300; i++) {
                assertThat(closer.service().assist(scope, id, "повтори")).isEqualTo("Ответ.");
            }
            String other = UUID.randomUUID().toString();
            assertThat(closer.service().assist(scope, other, "другой вопрос")).isEqualTo("Ответ.");
        }
        verify(gateway, times(2)).generateText(any());
    }

    @Test void theSlotIsHeldExactlyWhileTheProviderRuns() throws Exception {
        var gateway = mock(ModelGateway.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.generateText(any())).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return ModelTextResponse.text("Готово.", "test", "test", Duration.ZERO);
        });
        try (var closer = new ServiceCloser(new GlassesAssistService(gateway, true, 2000))) {
            var service = closer.service();
            var first = new java.util.concurrent.atomic.AtomicReference<String>();
            Thread caller = new Thread(() -> first.set(service.assist(scope, UUID.randomUUID().toString(), "первый")));
            caller.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            // While the provider is still answering the first question, a second one is refused at once.
            assertThatThrownBy(() -> service.assist(scope, UUID.randomUUID().toString(), "второй"))
                    .satisfies(e -> assertThat(((GlassesFailure) e).code).isEqualTo("BUSY"));
            release.countDown();
            caller.join(2000);
            assertThat(first.get()).isEqualTo("Готово.");
            // The moment the first answer is out, the slot is free — no handoff window, no retry loop.
            assertThat(service.assist(scope, UUID.randomUUID().toString(), "третий")).isEqualTo("Готово.");
        }
    }

    @Test void aTimedOutProviderKeepsTheSlotUntilItReallyEnds() throws Exception {
        var gateway = mock(ModelGateway.class);
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var ended = new CountDownLatch(1);
        when(gateway.generateText(any())).thenAnswer(invocation -> {
            // A provider that ignores cancellation: it ends only when told, like a slow HTTP call would.
            while (!done.get()) Thread.onSpinWait();
            ended.countDown();
            return ModelTextResponse.text("поздно", "test", "test", Duration.ZERO);
        });
        try (var closer = new ServiceCloser(new GlassesAssistService(gateway, true, 100))) {
            var service = closer.service();
            assertThatThrownBy(() -> service.assist(scope, UUID.randomUUID().toString(), "вопрос"))
                    .satisfies(e -> assertThat(((GlassesFailure) e).status).isEqualTo(503));
            // The client timed out, but the provider is still running: the slot is honestly busy.
            assertThatThrownBy(() -> service.assist(scope, UUID.randomUUID().toString(), "ещё"))
                    .satisfies(e -> assertThat(((GlassesFailure) e).code).isEqualTo("BUSY"));
            done.set(true);
            assertThat(ended.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(whenSlotAvailable(() -> service.assist(scope, UUID.randomUUID().toString(), "после"))).isEqualTo("поздно");
        }
    }

    // Kept for the one case where a provider that ignores cancellation releases the slot a moment after
    // it signals that it ended. Ordinary back-to-back calls no longer need it: see
    // aRequestRightAfterAnAnswerIsAdmittedEveryTime.
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
