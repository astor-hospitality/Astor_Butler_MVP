package museon_online.astor_butler.domain.calllog;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link CallLogModelGatewayPostProcessor} only while {@code astor.call-log.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "astor.call-log", name = "enabled", havingValue = "true")
public class CallLogModelGatewayConfiguration {

    /** Static and lazy: the writer (and its JdbcTemplate) is looked up at the first model call, not while post-processors are built. */
    @Bean
    static CallLogModelGatewayPostProcessor callLogModelGatewayPostProcessor(ObjectProvider<CallLogWriter> writer) {
        return new CallLogModelGatewayPostProcessor(writer::getObject);
    }
}
