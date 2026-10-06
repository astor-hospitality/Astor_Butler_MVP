package museon_online.astor_butler.api.glasses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** One revocable pilot credential, bound server-side to a staff member and venue. */
@Component
public class GlassesAccess {
    private final String tokenSha256;
    private final String tenant;
    private final String staff;
    private final String expiresAt;

    public GlassesAccess(@Value("${astor.glasses.token-sha256:}") String tokenSha256,
                         @Value("${astor.glasses.tenant:}") String tenant,
                         @Value("${astor.glasses.staff:}") String staff,
                         @Value("${astor.glasses.expires-at:}") String expiresAt) {
        this.tokenSha256 = tokenSha256;
        this.tenant = tenant;
        this.staff = staff;
        this.expiresAt = expiresAt;
    }

    Scope check(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7
                || authorization.length() > 4096) {
            throw new GlassesFailure(401, "UNAUTHORIZED", "Bearer access required");
        }
        if (!tokenSha256.matches("[a-fA-F0-9]{64}")) {
            throw new GlassesFailure(503, "ACCESS_UNAVAILABLE", "Pilot access is not configured");
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256")
                    .digest(authorization.substring(7).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(tokenSha256), actual)) {
                throw new GlassesFailure(401, "UNAUTHORIZED", "Invalid bearer access");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try {
            if (tenant.isBlank() || staff.isBlank() || !Instant.now().isBefore(Instant.parse(expiresAt))) {
                throw new GlassesFailure(403, "FORBIDDEN", "Pilot scope missing or expired");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new GlassesFailure(403, "FORBIDDEN", "Pilot scope missing or expired");
        }
        return new Scope(tenant, staff);
    }

    record Scope(String tenant, String staff) { }
}
