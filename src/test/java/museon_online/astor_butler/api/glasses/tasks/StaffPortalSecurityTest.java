package museon_online.astor_butler.api.glasses.tasks;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StaffPortalSecurityTest {
    static final String ISSUER = "https://keycloak.example.test/realms/astor";
    static final KeyPair KEYS = keys();
    static boolean enabled = true;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Configuration @EnableWebSecurity @EnableWebMvc
    static class Harness {
        @Bean StaffPortalService portal() {
            var portal = mock(StaffPortalService.class);
            when(portal.dashboard(any())).thenAnswer(call -> {
                StaffScope scope = call.getArgument(0);
                if (!scope.manages()) throw new StaffTaskFailure(403, "FORBIDDEN", "Manager required");
                return new StaffPortalService.Dashboard(scope.tenant(), List.of(), List.of(), true);
            });
            return portal;
        }
        @Bean StaffPortalController controller(StaffPortalService portal) { return new StaffPortalController(portal); }
        @Bean StaffLoginConfigurationController login() { return new StaffLoginConfigurationController(enabled, ISSUER); }
        @Bean @Order(1) SecurityFilterChain security(HttpSecurity http) throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(StaffPortalConfiguration.validator(ISSUER));
            var beans = new StaticListableBeanFactory(); beans.addBean("staffJwtDecoder", decoder);
            return new StaffPortalConfiguration().staffSecurity(http, enabled, beans.getBeanProvider(JwtDecoder.class));
        }
        @Bean @Order(2) SecurityFilterChain legacyPermitAll(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable()).build();
        }
    }
    @BeforeEach void start() { enabled = true; open(); }
    private void open() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Harness.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { context.close(); enabled = true; }
    private String token(String issuer, String audience, Instant expiry, String tenant, String role, KeyPair pair) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("manager").audience(audience)
                .issueTime(Date.from(Instant.now())).claim("tenant", tenant).claim("realm_access", Map.of("roles", List.of(role)));
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        token.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate())); return token.serialize();
    }
    private String good() throws Exception { return token(ISSUER, "astor-api", Instant.now().plusSeconds(300), "AERIS", "astor-manager", KEYS); }
    @Test void anonymousCannotReadOrMutate() throws Exception {
        mvc.perform(get("/api/admin/staff-tasks/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/staff-tasks").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(context.getBean(StaffPortalService.class));
    }
    @Test void validatesSignatureIssuerAudienceAndRequiredExpiry() throws Exception {
        for (String token : List.of(
                token(ISSUER, "astor-api", Instant.now().plusSeconds(300), "AERIS", "astor-manager", keys()),
                token("https://other.example/realms/astor", "astor-api", Instant.now().plusSeconds(300), "AERIS", "astor-manager", KEYS),
                token(ISSUER, "account", Instant.now().plusSeconds(300), "AERIS", "astor-manager", KEYS),
                token(ISSUER, "astor-api", Instant.now().minusSeconds(300), "AERIS", "astor-manager", KEYS),
                token(ISSUER, "astor-api", null, "AERIS", "astor-manager", KEYS))) {
            mvc.perform(get("/api/admin/staff-tasks/dashboard").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Test void callerScopeComesOnlyFromValidatedJwt() throws Exception {
        mvc.perform(get("/api/admin/staff-tasks/dashboard?tenant=OTHER").header("Authorization", "Bearer " + good()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenant").value("AERIS"));
        verify(context.getBean(StaffPortalService.class)).dashboard(new StaffScope("AERIS", "manager", StaffScope.Role.MANAGER));
    }
    @Test void missingTenantRolesAndWaiterCannotUseManagerRoutes() throws Exception {
        for (String token : List.of(
                token(ISSUER, "astor-api", Instant.now().plusSeconds(300), "", "astor-manager", KEYS),
                token(ISSUER, "astor-api", Instant.now().plusSeconds(300), "AERIS", "unrelated", KEYS),
                token(ISSUER, "astor-api", Instant.now().plusSeconds(300), "AERIS", "astor-waiter", KEYS))) {
            mvc.perform(get("/api/admin/staff-tasks/dashboard").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void featureDisabledNeverFallsThroughToPermitAll() throws Exception {
        context.close(); enabled = false; open();
        mvc.perform(get("/api/admin/staff-tasks/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/staff/login-config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        verifyNoInteractions(context.getBean(StaffPortalService.class));
    }
    @Test void oversizedCommandNeverReachesController() throws Exception {
        mvc.perform(post("/api/admin/staff-tasks").header("Authorization", "Bearer " + good())
                .contentType("application/json").content("x".repeat(65537)))
                .andExpect(status().isPayloadTooLarge());
    }
    @Test void noFakePhotoAcknowledgement() throws Exception {
        mvc.perform(post("/api/staff/tasks/task-1/evidence").header("Authorization", "Bearer " + good()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("EVIDENCE_UNAVAILABLE"));
    }
    @Test void pathParametersBindWithoutCompilerParameterMetadata() throws Exception {
        String bearer = "Bearer " + good();
        mvc.perform(get("/api/admin/staff-tasks/task-1/history").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/staff-tasks/task-1/cancel").header("Authorization", bearer)
                .contentType("application/json").content("{\"eventId\":\"event-1\",\"expectedVersion\":2}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/staff/members/anna").header("Authorization", bearer)
                .contentType("application/json").content("{\"displayName\":\"Anna\",\"role\":\"WAITER\",\"active\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/staff/members/anna/shift").header("Authorization", bearer)
                .contentType("application/json").content("{\"open\":true}"))
                .andExpect(status().isNoContent());
        var manager = new StaffScope("AERIS", "manager", StaffScope.Role.MANAGER);
        var portal = context.getBean(StaffPortalService.class);
        verify(portal).history(manager, "task-1"); verify(portal).cancel(manager, "task-1", "event-1", 2);
        verify(portal).member(manager, "anna", "Anna", "WAITER", true); verify(portal).shift(manager, "anna", true, null);
    }
    @Test void identityEndpointsMustUseHttps() {
        assertThatThrownBy(() -> StaffPortalConfiguration.https("http://keycloak.example/realms/astor"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void timestampContractMatchesBrowserIsoParsing() throws Exception {
        Instant at = Instant.parse("2026-10-05T09:00:00Z");
        StaffTask task = new StaffTask("task-1", "AERIS", "Manager", "13", "Prepare", "Check setting",
                List.of(new StaffTask.Stage("s1", "Setting", false, false, 0)), StaffTask.Priority.NORMAL,
                at, "anna", StaffTask.Status.ASSIGNED, 1, at, at);
        doReturn(new StaffPortalService.Dashboard("AERIS", List.of(), List.of(task), true))
                .when(context.getBean(StaffPortalService.class)).dashboard(any());
        mvc.perform(get("/api/admin/staff-tasks/dashboard").header("Authorization", "Bearer " + good()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tasks[0].deadline").value("2026-10-05T09:00:00Z"))
                .andExpect(jsonPath("$.tasks[0].deliveredAt").value("2026-10-05T09:00:00Z"));
    }
}
