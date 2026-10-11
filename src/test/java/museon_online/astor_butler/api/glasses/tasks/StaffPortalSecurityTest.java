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
    /** What the portal says about proof photos: empty while they have no storage, as by default. */
    static Optional<StaffEvidence.Limits> evidence = Optional.empty();
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
            when(portal.evidenceLimits()).thenReturn(evidence);
            return portal;
        }
        @Bean StaffPortalController controller(StaffPortalService portal) { return new StaffPortalController(portal); }
        @Bean StaffLoginConfigurationController login() { return new StaffLoginConfigurationController(enabled, ISSUER); }
        @Bean @Order(1) SecurityFilterChain security(HttpSecurity http, StaffPortalService portal) throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(StaffPortalConfiguration.validator(ISSUER));
            var beans = new StaticListableBeanFactory(); beans.addBean("staffJwtDecoder", decoder);
            beans.addBean("staffPortalService", portal);
            return new StaffPortalConfiguration().staffSecurity(http, enabled, beans.getBeanProvider(JwtDecoder.class),
                    beans.getBeanProvider(StaffPortalService.class));
        }
        @Bean @Order(2) SecurityFilterChain legacyPermitAll(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable()).build();
        }
    }
    @BeforeEach void start() { enabled = true; evidence = Optional.empty(); open(); }
    private void open() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Harness.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { context.close(); enabled = true; evidence = Optional.empty(); }
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
    @Test void aPhotoPassesTheJsonBodyLimitOnlyOnItsOwnRouteAndOnlyWhileItHasAStorage() throws Exception {
        String bearer = "Bearer " + good(), route = "/api/staff/tasks/task-1/evidence?eventId=photo-1&stageCode=setting";
        byte[] photo = InMemoryStaffEvidenceStorage.jpeg(100 * 1024);
        // No storage: the route is bounded like every other staff route, exactly as before.
        mvc.perform(post(route).header("Authorization", bearer).contentType("image/jpeg").content(photo))
                .andExpect(status().isPayloadTooLarge());
        verify(context.getBean(StaffPortalService.class), never()).evidence(any(), any(), any(), any(), any(), any(), any());

        context.close(); evidence = Optional.of(new StaffEvidence.Limits(200 * 1024, Set.of("image/jpeg"))); open();
        var portal = context.getBean(StaffPortalService.class);
        var caller = new StaffScope("AERIS", "manager", StaffScope.Role.MANAGER);
        mvc.perform(post(route).header("Authorization", bearer).contentType("image/jpeg").content(photo))
                .andExpect(status().isOk());
        var order = inOrder(portal);
        order.verify(portal).evidenceUpload(caller, "task-1");
        order.verify(portal).evidence(eq(caller), eq("task-1"), eq("photo-1"), eq("setting"), isNull(), eq("image/jpeg"), eq(photo));

        mvc.perform(post(route).header("Authorization", bearer).contentType("image/jpeg")
                        .content(InMemoryStaffEvidenceStorage.jpeg(200 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
        // Every other route keeps the JSON limit, also one that only looks like the photo route.
        for (String other : List.of("/api/staff/tasks/task-1/commands", "/api/staff/tasks/task-1/evidence/extra",
                "/api/admin/staff-tasks/task-1/evidence", "/api/staff/tasks/evidence")) {
            mvc.perform(post(other).header("Authorization", bearer).contentType("image/jpeg").content(photo))
                    .andExpect(status().isPayloadTooLarge());
        }
        verify(portal, times(1)).evidence(any(), any(), any(), any(), any(), any(), any());
    }
    @Test void evidenceRoutesNeedAStaffTokenAndServeAPhotoUnderItsRecordedType() throws Exception {
        context.close(); evidence = Optional.of(new StaffEvidence.Limits(200 * 1024, Set.of("image/jpeg"))); open();
        var portal = context.getBean(StaffPortalService.class);
        byte[] photo = InMemoryStaffEvidenceStorage.png();
        mvc.perform(post("/api/staff/tasks/task-1/evidence?eventId=photo-1&stageCode=setting").contentType("image/jpeg")
                .content(InMemoryStaffEvidenceStorage.jpeg(64))).andExpect(status().isUnauthorized());
        for (String route : List.of("/api/staff/tasks/task-1/evidence", "/api/staff/tasks/task-1/evidence/photo-1",
                "/api/admin/staff-tasks/task-1/evidence", "/api/admin/staff-tasks/task-1/evidence/photo-1")) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(portal);

        var caller = new StaffScope("AERIS", "manager", StaffScope.Role.MANAGER);
        Instant at = Instant.parse("2026-10-11T09:00:00Z");
        var file = new StaffEvidence.File(new StaffEvidence("photo-1", "task-1", "setting", 4, "event-1", "anna",
                "image/png", photo.length, "0".repeat(64), null, at, at.plusSeconds(60)), photo);
        doReturn(file).when(portal).evidenceFileForManager(caller, "task-1", "photo-1");
        doReturn(file).when(portal).evidenceFileForAssignee(caller, "task-1", "photo-1");
        doReturn(List.of(file.evidence())).when(portal).evidenceForManager(caller, "task-1");
        String bearer = "Bearer " + good();
        for (String route : List.of("/api/staff/tasks/task-1/evidence/photo-1", "/api/admin/staff-tasks/task-1/evidence/photo-1")) {
            mvc.perform(get(route).header("Authorization", bearer)).andExpect(status().isOk())
                    .andExpect(content().bytes(photo)).andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        mvc.perform(get("/api/admin/staff-tasks/task-1/evidence").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].evidenceId").value("photo-1"))
                .andExpect(jsonPath("$.items[0].versionAfter").value(4))
                .andExpect(jsonPath("$.items[0].receivedAt").value("2026-10-11T09:00:00Z"));
        mvc.perform(get("/api/staff/tasks/task-1/evidence").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        verify(portal).evidenceForAssignee(caller, "task-1");

        context.close(); enabled = false; open();
        mvc.perform(post("/api/staff/tasks/task-1/evidence?eventId=photo-1&stageCode=setting").header("Authorization", bearer)
                .contentType("image/jpeg").content(InMemoryStaffEvidenceStorage.jpeg(64))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/staff-tasks/task-1/evidence/photo-1").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(context.getBean(StaffPortalService.class));
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
