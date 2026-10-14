package museon_online.astor_butler.model;

/**
 * One chat turn. {@code role} is {@code system}, {@code user}, {@code assistant} or {@code function};
 * a {@code function} turn carries the tool name in {@code name} and its JSON result in {@code content};
 * an {@code assistant} turn that called a tool carries the call in {@code functionCall}.
 */
public record ModelChatMessage(String role, String content, String name, ModelToolCall functionCall) {

    public static ModelChatMessage system(String content) {
        return new ModelChatMessage("system", content, null, null);
    }

    public static ModelChatMessage user(String content) {
        return new ModelChatMessage("user", content, null, null);
    }

    public static ModelChatMessage assistant(String content) {
        return new ModelChatMessage("assistant", content, null, null);
    }

    public static ModelChatMessage assistantCall(String content, ModelToolCall call) {
        return new ModelChatMessage("assistant", content, null, call);
    }

    public static ModelChatMessage functionResult(String name, String jsonContent) {
        return new ModelChatMessage("function", jsonContent, name, null);
    }
}
