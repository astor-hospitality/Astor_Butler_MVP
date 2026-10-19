package museon_online.astor_butler.api.board;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Whether the Kafka of the guest events answers, for the board's architecture map. Kept out of
 * {@code /actuator/health} on purpose: Kafka is not on the booking path (events wait in the PostgreSQL outbox), so a
 * Kafka outage must not mark the Butler itself unhealthy.
 */
@Component
public class KafkaProbe implements DisposableBean {

    static final int TIMEOUT_MS = 2000;

    private final boolean enabled;
    private final String bootstrapServers;
    private AdminClient client;

    public KafkaProbe(@Value("${astor.kafka.enabled:true}") boolean enabled,
                      @Value("${astor.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.enabled = enabled;
        this.bootstrapServers = bootstrapServers;
    }

    /** The brokers that answered within two seconds; empty when Kafka is switched off. Throws when it does not answer. */
    public OptionalInt brokers() throws Exception {
        if (!enabled) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(client().describeCluster(new DescribeClusterOptions().timeoutMs(TIMEOUT_MS))
                .nodes().get(TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS).size());
    }

    private synchronized AdminClient client() {
        if (client == null) {
            Properties properties = new Properties();
            properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(TIMEOUT_MS));
            properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(TIMEOUT_MS));
            client = AdminClient.create(properties);
        }
        return client;
    }

    @Override
    public synchronized void destroy() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
