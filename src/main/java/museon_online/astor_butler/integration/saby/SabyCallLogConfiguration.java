package museon_online.astor_butler.integration.saby;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Switched on by {@code astor.billing.saby-call-log-enabled}. The interceptor is added to every RestTemplate the
 * application builds, because each Saby client builds its own; calls to any other host pass through it unrecorded.
 */
@Configuration
@ConditionalOnProperty(prefix = "astor.billing", name = "saby-call-log-enabled", havingValue = "true")
class SabyCallLogConfiguration {

    @Bean
    SabyCallLogInterceptor sabyCallLogInterceptor(SabyReservationProperties properties, SabyCallLogRepository callLog) {
        return new SabyCallLogInterceptor(properties, callLog);
    }

    @Bean
    RestTemplateCustomizer sabyCallLogCustomizer(SabyCallLogInterceptor interceptor) {
        return restTemplate -> restTemplate.getInterceptors().add(interceptor);
    }
}
