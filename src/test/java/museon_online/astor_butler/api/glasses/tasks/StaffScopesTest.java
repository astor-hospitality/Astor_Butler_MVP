package museon_online.astor_butler.api.glasses.tasks;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaffScopesTest {
    private final StaffScopes scopes = new StaffScopes("astor-api");

    private static Map<String, Object> token(Object audience, String... realmRoles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("aud", audience);
        claims.put("sub", "5f0c1c1e-staff");
        claims.put("tenant", "AERIS");
        claims.put("realm_access", Map.of("roles", List.of(realmRoles)));
        return claims;
    }

    private static void refused(ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StaffTaskFailure.class,
                failure -> assertThat(failure.status).isEqualTo(status));
    }

    @Test void validTokenBecomesScopeOfItsVenueAndRole() {
        assertThat(scopes.from(token("astor-api", "astor-waiter")))
                .isEqualTo(new StaffScope("AERIS", "5f0c1c1e-staff", StaffScope.Role.WAITER));
        assertThat(scopes.from(token(List.of("account", "astor-api"), "astor-hostess")).role())
                .isEqualTo(StaffScope.Role.HOSTESS);
    }

    @Test void widestRoleWinsAndClientRolesCount() {
        assertThat(scopes.from(token("astor-api", "astor-waiter", "astor-manager", "offline_access")).role())
                .isEqualTo(StaffScope.Role.MANAGER);

        Map<String, Object> clientOnly = token("astor-api");
        clientOnly.put("resource_access", Map.of("astor-api", Map.of("roles", List.of("astor-hostess"))));
        assertThat(scopes.from(clientOnly).role()).isEqualTo(StaffScope.Role.HOSTESS);
    }

    @Test void tokenForAnotherClientIsNotAccepted() {
        refused(() -> scopes.from(token("vedal-portal", "astor-manager")), 401);
        refused(() -> scopes.from(token(List.of("account"), "astor-manager")), 401);
        refused(() -> scopes.from(token(null, "astor-manager")), 401);
        refused(() -> scopes.from(null), 401);
    }

    @Test void rolesOfAnotherClientGiveNothing() {
        Map<String, Object> claims = token("astor-api");
        claims.put("resource_access", Map.of("vedal-portal", Map.of("roles", List.of("astor-manager"))));
        refused(() -> scopes.from(claims), 403);
        refused(() -> scopes.from(token("astor-api", "portal-admin")), 403);
    }

    @Test void accountWithoutSubjectOrSingleVenueIsRefused() {
        Map<String, Object> noSubject = token("astor-api", "astor-waiter");
        noSubject.remove("sub");
        refused(() -> scopes.from(noSubject), 401);

        Map<String, Object> noVenue = token("astor-api", "astor-waiter");
        noVenue.remove("tenant");
        refused(() -> scopes.from(noVenue), 403);

        Map<String, Object> blankVenue = token("astor-api", "astor-waiter");
        blankVenue.put("tenant", "  ");
        refused(() -> scopes.from(blankVenue), 403);

        Map<String, Object> twoVenues = token("astor-api", "astor-waiter");
        twoVenues.put("tenant", List.of("AERIS", "OTHER"));
        refused(() -> scopes.from(twoVenues), 403);
    }

    @Test void malformedRoleClaimsAreIgnoredNotTrusted() {
        Map<String, Object> claims = token("astor-api");
        claims.put("realm_access", "astor-manager");
        claims.put("resource_access", List.of("astor-manager"));
        refused(() -> scopes.from(claims), 403);
    }
}
