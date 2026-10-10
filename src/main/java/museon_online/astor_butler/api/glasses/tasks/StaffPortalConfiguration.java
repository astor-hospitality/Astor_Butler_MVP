package museon_online.astor_butler.api.glasses.tasks;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.net.URI;
import java.util.Map;

/** The JWT portal: decoder and routes. The task service itself lives in {@link StaffTasksConfiguration}. */
@Configuration
public class StaffPortalConfiguration {
    @Bean("staffJwtDecoder") @ConditionalOnProperty(name="astor.staff.enabled", havingValue="true")
    JwtDecoder staffJwtDecoder(@Value("${astor.staff.issuer-uri}") String issuer,
                               @Value("${astor.staff.jwk-set-uri}") String keys) {
        https(issuer); https(keys);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(keys).build();
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }
    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> jwt.getExpiresAt() != null && jwt.getAudience().contains("astor-api")
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token")));
    }
    @Bean @Order(1)
    SecurityFilterChain staffSecurity(HttpSecurity http,
            @Value("${astor.staff.enabled:false}") boolean enabled,
            @Qualifier("staffJwtDecoder") ObjectProvider<JwtDecoder> decoder) throws Exception {
        http.securityMatcher("/api/staff/**", "/api/admin/staff/**", "/api/admin/staff-tasks/**")
                .cors(Customizer.withDefaults()).csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/api/staff/login-config").permitAll();
                    if (enabled) a.anyRequest().authenticated(); else a.anyRequest().denyAll();
                })
                .exceptionHandling(e -> e.authenticationEntryPoint((r, s, ex) -> error(s, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((r, s, ex) -> error(s, 403, "FORBIDDEN")))
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable());
        if (enabled) {
            http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder.getObject()))
                    .authenticationEntryPoint((r, s, ex) -> error(s, 401, "UNAUTHORIZED")));
            http.addFilterAfter(new StaffBodyLimitFilter(),
                    org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }
    static void https(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Staff identity URLs must use HTTPS");
    }
    private static void error(jakarta.servlet.http.HttpServletResponse response, int status, String code) throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"Staff access denied\"}}");
    }
}
