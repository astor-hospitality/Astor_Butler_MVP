package museon_online.astor_butler.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Butler's agent, switched by {@code ASTOR_AGENT_ENABLED} (off by default). In the Butler's own bot it answers
 * only chats in {@code ASTOR_AGENT_ALLOWLIST} (comma-separated chat ids; empty — nobody). {@code model}
 * ({@code ASTOR_AGENT_MODEL}) names the model of agent chats; one model call waits {@code modelTimeoutMs} at most, a
 * whole guest turn {@code turnTimeoutMs} ({@code ASTOR_AGENT_TURN_TIMEOUT_MS}).
 */
@Component
@ConfigurationProperties(prefix = "astor.agent")
@Getter
@Setter
public class AgentProperties {

    private boolean enabled = false;
    private String allowlist = "";
    private String model = "GigaChat-3-Pro";
    /** Under the Concierge's own wait for a negotiation turn (30 s), so it never gives up on a turn still running. */
    private long turnTimeoutMs = 25_000;
    private long modelTimeoutMs = 20_000;
    private int maxToolCalls = 5;
    private int maxTokens = 512;
    /** About 2k tokens of earlier turns; older ones collapse into one «Ранее: …» line. */
    private int historyChars = 8_000;

    public Set<Long> allowedChats() {
        if (allowlist == null || allowlist.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowlist.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(AgentProperties::parse)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether the agent answers this chat of the Butler's own bot. */
    public boolean allows(Long chatId) {
        return enabled && chatId != null && allowedChats().contains(chatId);
    }

    private static Long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
