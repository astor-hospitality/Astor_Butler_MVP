package museon_online.astor_butler.capability;

import java.util.Map;

/**
 * One tool of the Capability API: the Concierge calls it over REST, the Butler's agent in-process. It returns facts,
 * never text for the guest. {@code parameters} is a JSON Schema object the model reads.
 */
public interface CapabilityTool {

    String name();

    String description();

    Map<String, Object> parameters();

    /** Whether the tool commits the venue to something (a booking, an order); such calls are idempotent by key. */
    boolean commitment();

    CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args);
}
