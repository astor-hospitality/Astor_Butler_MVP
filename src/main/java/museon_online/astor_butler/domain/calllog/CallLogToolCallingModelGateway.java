package museon_online.astor_butler.domain.calllog;

import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ToolCallingModel;

import java.util.function.Supplier;

/**
 * {@link CallLogModelGateway} for a gateway that also calls tools (GigaChat): every {@link #chat} of the agents and
 * the visit summaries is one {@code llm} row under the request's model (e.g. {@code astor.agent.model}), with the
 * answer's token usage. Like the text calls, the answer and any exception pass through untouched.
 */
public class CallLogToolCallingModelGateway extends CallLogModelGateway implements ToolCallingModel {

    private final ToolCallingModel tools;

    public CallLogToolCallingModelGateway(ModelGateway delegate, Supplier<CallLogWriter> writer) {
        super(delegate, writer);
        if (!(delegate instanceof ToolCallingModel model)) {
            throw new IllegalArgumentException("The delegate does not call tools");
        }
        this.tools = model;
    }

    @Override
    public ModelChatResponse chat(ModelChatRequest request) {
        long started = System.nanoTime();
        String requested = request == null ? null : request.model();
        ModelChatResponse response;
        try {
            response = tools.chat(request);
        } catch (RuntimeException e) {
            failed(CallLogEntry.TYPE_LLM, requested, e, started);
            throw e;
        }
        if (response != null) {
            String model = response.model() == null || response.model().isBlank() ? requested : response.model();
            answered(CallLogEntry.TYPE_LLM, model == null || model.isBlank() ? response.provider() : model,
                    false, response.metadata(), started);
        }
        return response;
    }
}
