package museon_online.astor_butler.api.capability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal: the Concierge's way into the Capability API (guarded by the internal token). Every answer is 200 with a
 * {@link CapabilityResult}: an unknown tool, a bad argument or a switched-off API are facts, not HTTP failures.
 */
@RestController
@RequestMapping("/api/capabilities/v1")
@Tag(name = "Capabilities", description = "Internal: tools the Concierge and the Butler's agent call")
public class CapabilityController {

    private final CapabilityRegistry registry;

    public CapabilityController(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/tools")
    @Operation(summary = "List tools with their JSON Schemas")
    public List<Map<String, Object>> tools() {
        return registry.tools().stream().map(tool -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("parameters", tool.parameters());
            entry.put("commitment", tool.commitment());
            return entry;
        }).toList();
    }

    /** Over REST the caller is always the Concierge; the Butler's agent calls the registry in-process. */
    @PostMapping("/{tool}")
    @Operation(summary = "Invoke a tool")
    public CapabilityResult invoke(@PathVariable("tool") String tool, @RequestBody(required = false) InvokeRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        CapabilityContext given = request == null ? null : request.context();
        String key = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey
                : given == null ? null : given.idempotencyKey();
        CapabilityContext context = given == null
                ? new CapabilityContext(CapabilityContext.CONCIERGE, null, null, null, key, null, null)
                : new CapabilityContext(CapabilityContext.CONCIERGE, given.chatId(), given.sessionId(), given.visitId(), key,
                        given.guestName(), given.guestPhone());
        Map<String, Object> arguments = request == null || request.arguments() == null ? Map.of() : request.arguments();
        return registry.invoke(tool, context, arguments);
    }

    public record InvokeRequest(CapabilityContext context, Map<String, Object> arguments) {
    }
}
