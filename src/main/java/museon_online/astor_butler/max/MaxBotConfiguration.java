package museon_online.astor_butler.max;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Binds {@code astor.max.*}. Only the settings bean is always present (it is cheap and lets the rest of the code
 * ask whether MAX is on); the API client, the router and the polling loop are created in {@link MaxChannelConfiguration}
 * only when {@code astor.max.enabled=true}.
 */
@Slf4j
@Configuration
public class MaxBotConfiguration {

    @Bean
    public MaxBotSettings maxBotSettings(
            @Value("${astor.max.enabled:false}") boolean enabled,
            @Value("${astor.max.token:}") String token,
            @Value("${astor.max.base-url:" + MaxBotSettings.DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${astor.max.ca-cert-path:}") String caCertPath,
            @Value("${astor.max.poll-timeout-seconds:30}") long pollTimeoutSeconds,
            @Value("${astor.max.poll-limit:100}") int pollLimit,
            @Value("${astor.max.request-timeout-ms:10000}") long requestTimeoutMs,
            @Value("${astor.max.retry-delay-ms:5000}") long retryDelayMs,
            @Value("${astor.max.group-chats-enabled:false}") boolean groupChatsEnabled,
            @Value("${astor.max.admin-alerts-to-telegram:true}") boolean adminAlertsToTelegram
    ) {
        MaxBotSettings settings = new MaxBotSettings(
                enabled,
                token,
                MaxBotSettings.baseUrl(baseUrl),
                caCertPath,
                Duration.ofSeconds(pollTimeoutSeconds),
                pollLimit,
                Duration.ofMillis(requestTimeoutMs),
                Duration.ofMillis(retryDelayMs),
                groupChatsEnabled,
                adminAlertsToTelegram
        );
        if (settings.enabled()) {
            // Never log the token itself.
            log.info("MAX channel enabled: baseUrl={}, tokenPresent={}, caCertPath={}, pollTimeout={}s, groupChats={}",
                    settings.baseUrl(), !settings.token().isEmpty(),
                    settings.caCertPath().isEmpty() ? "(JDK trust store only)" : settings.caCertPath(),
                    settings.pollTimeout().toSeconds(), settings.groupChatsEnabled());
        }
        return settings;
    }
}
