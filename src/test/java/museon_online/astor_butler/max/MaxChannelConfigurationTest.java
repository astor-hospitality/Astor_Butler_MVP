package museon_online.astor_butler.max;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.messenger.MessengerChatBindingRepository;
import museon_online.astor_butler.fsm.core.idempotency.IdempotencyService;
import museon_online.astor_butler.max.adapter.MaxLongPollingRunner;
import museon_online.astor_butler.max.adapter.MaxRouter;
import museon_online.astor_butler.max.client.MaxBotApiClient;
import museon_online.astor_butler.service.message.MessageGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** With the defaults the MAX channel does not exist; switched on, it is wired but idles without a token. */
class MaxChannelConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MaxBotConfiguration.class, MaxChannelConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MessengerChatBindingRepository.class, () -> mock(MessengerChatBindingRepository.class))
            .withBean(IdempotencyService.class, () -> mock(IdempotencyService.class))
            .withBean(MessageGatewayService.class, () -> mock(MessageGatewayService.class));

    @Test
    void offByDefaultNoClientNoRouterNoPollingThread() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MaxBotSettings.class).enabled()).isFalse();
            assertThat(context.getBean(MaxBotSettings.class).baseUrl().toString()).isEqualTo("https://platform-api2.max.ru");
            assertThat(context).doesNotHaveBean(MaxBotApiClient.class);
            assertThat(context).doesNotHaveBean(MaxRouter.class);
            assertThat(context).doesNotHaveBean(MaxLongPollingRunner.class);
        });
    }

    @Test
    void enabledWithoutATokenIsWiredButDoesNotPoll() {
        runner.withPropertyValues("astor.max.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MaxRouter.class);
            assertThat(context.getBean(MaxLongPollingRunner.class).isRunning()).isFalse();
        });
    }

    @Test
    void settingsAreClampedToWhatTheApiAccepts() {
        runner.withPropertyValues(
                "astor.max.enabled=true",
                "astor.max.base-url=https://platform-api2.max.ru///",
                "astor.max.poll-timeout-seconds=600",
                "astor.max.poll-limit=5000",
                "astor.max.ca-cert-path=/nonexistent/ca.pem"
        ).run(context -> {
            MaxBotSettings settings = context.getBean(MaxBotSettings.class);
            assertThat(settings.baseUrl().toString()).isEqualTo("https://platform-api2.max.ru");
            assertThat(settings.pollTimeout().toSeconds()).isEqualTo(90);
            assertThat(settings.pollLimit()).isEqualTo(1000);
            assertThat(settings.active()).isFalse();
            // A wrong CA path breaks MAX calls, not the application.
            assertThat(context).hasNotFailed();
        });
    }
}
