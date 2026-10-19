package museon_online.astor_butler.domain.summary;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Visit summaries, switched by {@code ASTOR_VISIT_SUMMARY_ENABLED} (off by default). {@code model}
 * ({@code ASTOR_SUMMARY_MODEL}) overrides the model name; blank keeps the gateway's configured model.
 */
@Component
@ConfigurationProperties(prefix = "astor.summary")
@Getter
@Setter
public class SummaryProperties {

    private boolean enabled = false;
    private String model = "";
}
