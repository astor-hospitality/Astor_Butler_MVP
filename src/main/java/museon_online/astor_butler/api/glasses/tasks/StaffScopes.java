package museon_online.astor_butler.api.glasses.tasks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Turns the claims of an access token into the caller's scope.
 *
 * The token must already be validated by the transport: signature, issuer and expiry. This class only
 * decides what a valid token is allowed to be here. Keycloak puts roles into realm_access.roles and
 * resource_access.<client>.roles, not into scope, so both places are read.
 */
public final class StaffScopes {
    private final String audience;

    /** @param audience client id of the Astor API in Keycloak; a token issued for another client is refused */
    public StaffScopes(String audience) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("Audience is required");
        this.audience = audience;
    }

    public StaffScope from(Map<String, Object> claims) {
        if (claims == null || !strings(claims.get("aud")).contains(audience)) {
            // Another client of the same realm signs with the same keys; the audience is the only difference.
            throw new StaffTaskFailure(401, "UNAUTHORIZED", "The token is not issued for Astor");
        }
        String staffId = text(claims.get("sub"));
        if (staffId.isBlank()) throw new StaffTaskFailure(401, "UNAUTHORIZED", "The token has no subject");

        // One account works at one venue. A missing or multi-valued venue is refused rather than guessed.
        String tenant = claims.get("tenant") instanceof String value ? value.trim() : "";
        if (tenant.isBlank()) throw new StaffTaskFailure(403, "FORBIDDEN", "No venue is bound to this account");

        StaffScope.Role role = role(claims);
        if (role == null) throw new StaffTaskFailure(403, "FORBIDDEN", "The account has no Astor staff role");
        return new StaffScope(tenant, staffId, role);
    }

    /** The widest role wins when an account has several. */
    private StaffScope.Role role(Map<String, Object> claims) {
        List<String> roles = new ArrayList<>(roles(claims.get("realm_access")));
        if (claims.get("resource_access") instanceof Map<?, ?> resources) roles.addAll(roles(resources.get(audience)));
        if (roles.contains("astor-manager")) return StaffScope.Role.MANAGER;
        if (roles.contains("astor-hostess")) return StaffScope.Role.HOSTESS;
        if (roles.contains("astor-waiter")) return StaffScope.Role.WAITER;
        return null;
    }

    private static List<String> roles(Object holder) {
        return holder instanceof Map<?, ?> map ? strings(map.get("roles")) : List.of();
    }

    /** A claim that may be one string or a list of strings, such as aud. */
    private static List<String> strings(Object claim) {
        if (claim instanceof String value) return List.of(value);
        if (claim instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static String text(Object claim) {
        return claim instanceof String value ? value.trim() : "";
    }
}
