package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesAssistServiceTest {
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

    private record ServiceCloser(GlassesAssistService service) implements AutoCloseable {
        @Override public void close() { service.close(); }
    }
}
