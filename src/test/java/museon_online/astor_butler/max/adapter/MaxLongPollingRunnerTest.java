package museon_online.astor_butler.max.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.max.MaxBotSettings;
import museon_online.astor_butler.max.client.MaxBotApiClient;
import museon_online.astor_butler.max.client.MaxUpdatesPage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MaxLongPollingRunnerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MaxBotApiClient client = mock(MaxBotApiClient.class);
    private final MaxRouter router = mock(MaxRouter.class);

    private MaxBotSettings settings(String token) {
        return new MaxBotSettings(true, token, URI.create("https://example.invalid"), "", Duration.ofSeconds(30), 100,
                null, Duration.ofMillis(100), false, true);
    }

    @Test
    void eachPollSendsTheLastMarkerAndHandsEveryUpdateToTheRouter() throws Exception {
        var first = json.readTree("{\"update_type\":\"message_created\"}");
        var second = json.readTree("{\"update_type\":\"bot_started\"}");
        when(client.getUpdates(eq(null), any(), anyInt())).thenReturn(new MaxUpdatesPage(List.of(first, second), 10L));
        when(client.getUpdates(eq(10L), any(), anyInt())).thenReturn(new MaxUpdatesPage(List.of(), null));
        MaxLongPollingRunner runner = new MaxLongPollingRunner(client, router, settings("t"));

        assertThat(runner.pollOnce()).isEqualTo(2);
        assertThat(runner.marker()).isEqualTo(10L);
        assertThat(runner.pollOnce()).isZero();
        // An empty answer without a marker keeps the old one.
        assertThat(runner.marker()).isEqualTo(10L);
        verify(router).handle(first);
        verify(router).handle(second);
        verify(client).getUpdates(eq(10L), eq(Duration.ofSeconds(30)), eq(100));
    }

    @Test
    void oneFailingUpdateIsSkippedAndTheMarkerStillMoves() throws Exception {
        var bad = json.readTree("{\"update_type\":\"message_created\",\"n\":1}");
        var good = json.readTree("{\"update_type\":\"message_created\",\"n\":2}");
        when(client.getUpdates(any(), any(), anyInt())).thenReturn(new MaxUpdatesPage(List.of(bad, good), 77L));
        doThrow(new IllegalStateException("boom")).when(router).handle(bad);
        MaxLongPollingRunner runner = new MaxLongPollingRunner(client, router, settings("t"));

        assertThat(runner.pollOnce()).isEqualTo(2);
        assertThat(runner.marker()).isEqualTo(77L);
        verify(router, times(1)).handle(good);
    }

    @Test
    void announceNeverFailsEvenWhenMaxRejectsTheToken() throws Exception {
        when(client.getMe()).thenThrow(new museon_online.astor_butler.max.client.MaxApiException(401, "verify.token", "Invalid access_token"));
        MaxLongPollingRunner runner = new MaxLongPollingRunner(client, router, settings("t"));

        runner.announce();

        verify(client).getMe();
    }

    @Test
    void withoutATokenThePollingThreadIsNeverStarted() {
        MaxLongPollingRunner runner = new MaxLongPollingRunner(client, router, settings(""));

        runner.start();

        assertThat(runner.isRunning()).isFalse();
        verifyNoInteractions(client, router);
    }

    @Test
    void startedLoopPollsUntilStopped() throws Exception {
        when(client.getUpdates(any(), any(), anyInt())).thenAnswer(call -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                // The real client turns an interrupt into MaxApiException and keeps the flag; so does this stand-in.
                Thread.currentThread().interrupt();
                throw new museon_online.astor_butler.max.client.MaxApiException(0, "interrupted", e);
            }
            return new MaxUpdatesPage(List.of(), null);
        });
        MaxLongPollingRunner runner = new MaxLongPollingRunner(client, router, settings("t"));

        runner.start();
        verify(client, org.mockito.Mockito.timeout(2000).atLeast(2)).getUpdates(any(), any(), anyInt());
        runner.stop();

        assertThat(runner.isRunning()).isFalse();
    }
}
