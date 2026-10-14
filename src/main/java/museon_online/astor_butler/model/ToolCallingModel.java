package museon_online.astor_butler.model;

/** A model that can answer with a function call. */
public interface ToolCallingModel {

    /**
     * @throws ModelUnavailableException when the model cannot be used now (no quota, rejected credentials,
     *                                   timeout or a provider outage); callers fall back on it
     */
    ModelChatResponse chat(ModelChatRequest request);
}
