package museon_online.astor_butler.agent;

import museon_online.astor_butler.model.OpenAiCompatibleModelGateway;
import museon_online.astor_butler.model.ToolCallingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code ASTOR_AGENT_PROVIDER=openai-compatible}: the negotiation agent alone talks to the OpenAI-compatible API
 * ({@code OPENAI_COMPATIBLE_BASE_URL}, {@code OPENAI_COMPATIBLE_API_KEY}, model {@code ASTOR_AGENT_MODEL}), while
 * understanding, replies, summaries, embeddings and speech stay on {@code ASTOR_MODEL_PROVIDER}. Rollback is unsetting
 * the variable: the agent is back on the application's model (GigaChat) at the next start.
 *
 * <p>The bean is not a default candidate: plain {@code ModelGateway} / {@code ToolCallingModel} injection never sees it,
 * only {@link AgentLoop#setAgentModel} asks for it by qualifier. It is still a model gateway bean, so the call log
 * wraps it like the main one and its calls are counted under their model.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "astor.agent", name = "provider", havingValue = "openai-compatible")
public class AgentModelConfiguration {

    /** Qualifier of the agent's own model. */
    public static final String AGENT_MODEL = "astorAgentModel";

    @Bean(name = AGENT_MODEL, defaultCandidate = false)
    @Qualifier(AGENT_MODEL)
    ToolCallingModel astorAgentModel(
            RestTemplateBuilder restTemplateBuilder,
            AgentProperties agent,
            @Value("${astor.model.openai-compatible.base-url:}") String baseUrl,
            @Value("${astor.model.openai-compatible.api-key:}") String apiKey,
            @Value("${astor.model.openai-compatible.model:}") String model,
            @Value("${astor.model.openai-compatible.timeout-ms:15000}") int timeoutMs,
            @Value("${astor.model.openai-compatible.temperature:0.1}") double temperature,
            @Value("${astor.model.openai-compatible.parallel-tool-calls:}") String parallelToolCalls
    ) {
        // The agent always names its model (ASTOR_AGENT_MODEL); OPENAI_COMPATIBLE_MODEL is only the default for a
        // request without one, so the agent's model stands in when it is unset. No persona, vision or embeddings.
        String frontline = model == null || model.isBlank() ? agent.getModel() : model;
        OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(restTemplateBuilder, baseUrl, apiKey,
                frontline, "", "", "", timeoutMs, agent.getMaxTokens(), temperature, false, "");
        gateway.setParallelToolCalls(parallelToolCalls);
        return gateway;
    }
}
