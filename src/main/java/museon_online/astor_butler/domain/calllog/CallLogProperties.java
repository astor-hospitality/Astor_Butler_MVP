package museon_online.astor_butler.domain.calllog;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Off by default: with it off nothing is written to {@code call_log} (docs/operations/CALL_LOG.md). */
@Component
@ConfigurationProperties(prefix = "astor.call-log")
@Getter
@Setter
public class CallLogProperties {

    /** Write the unified call log ({@code ASTOR_CALL_LOG_ENABLED}). */
    private boolean enabled = false;

    /** Salt of the pseudonymous user ids ({@code ASTOR_CALL_LOG_SALT}); shorter than 16 characters means no ids at all. */
    private String salt = "";

    /** Tariff overrides, ₽ per 1M tokens by model-name prefix: {@code "[GigaChat-3-Pro]": "73,176"} (input,output). */
    private Map<String, String> tariffs = new LinkedHashMap<>();
}
