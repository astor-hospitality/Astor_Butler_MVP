package museon_online.astor_butler.max;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.messenger.MessengerChatBindingRepository;
import museon_online.astor_butler.fsm.core.idempotency.IdempotencyService;
import museon_online.astor_butler.max.adapter.MaxAdminAlertRelay;
import museon_online.astor_butler.max.adapter.MaxLongPollingRunner;
import museon_online.astor_butler.max.adapter.MaxReplyRenderer;
import museon_online.astor_butler.max.adapter.MaxRouter;
import museon_online.astor_butler.max.adapter.MaxUpdateMapper;
import museon_online.astor_butler.max.client.MaxBotApiClient;
import museon_online.astor_butler.service.message.MessageGatewayService;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The MAX channel beans. None of them exists unless {@code astor.max.enabled=true} ({@code ASTOR_MAX_ENABLED}), so
 * with the default the application starts exactly as before: no thread, no HTTP client, no MAX call. Enabled
 * without a token, the beans exist but the polling loop logs a warning and does not start.
 */
@Configuration
@ConditionalOnProperty(prefix = "astor.max", name = "enabled", havingValue = "true")
public class MaxChannelConfiguration {

    @Bean
    public MaxBotApiClient maxBotApiClient(MaxBotSettings settings, ObjectMapper objectMapper) {
        return new MaxBotApiClient(MaxBotApiClient.httpClient(settings.caCertPath()), objectMapper, settings.baseUrl(),
                settings.token(), settings.requestTimeout());
    }

    @Bean
    public MaxAdminAlertRelay maxAdminAlertRelay(
            ObjectProvider<TelegramBot> telegramBot,
            @Value("${telegram.bot.enabled:false}") boolean telegramEnabled,
            MaxBotSettings settings
    ) {
        return new MaxAdminAlertRelay(telegramBot, telegramEnabled, settings.adminAlertsToTelegram());
    }

    @Bean
    public MaxRouter maxRouter(
            MaxBotApiClient client,
            MessengerChatBindingRepository bindings,
            IdempotencyService idempotency,
            MessageGatewayService gateway,
            MaxAdminAlertRelay adminAlerts,
            MaxBotSettings settings
    ) {
        return new MaxRouter(client, new MaxUpdateMapper(), new MaxReplyRenderer(), bindings, idempotency, gateway,
                adminAlerts, settings);
    }

    @Bean
    public MaxLongPollingRunner maxLongPollingRunner(MaxBotApiClient client, MaxRouter router, MaxBotSettings settings) {
        return new MaxLongPollingRunner(client, router, settings);
    }
}
