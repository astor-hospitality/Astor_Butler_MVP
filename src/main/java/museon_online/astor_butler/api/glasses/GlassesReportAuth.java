package museon_online.astor_butler.api.glasses;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * The server-side password for the pages and tools people use, as opposed to the mobile bearer:
 * the shift report, and queueing a message for the staff member. One password, set in the server
 * environment; the mobile client never sees it.
 */
@Component
public class GlassesReportAuth {
    static final String USER = "astor";
    static final int RATE_LIMIT = 30;
    private final GlassesAccess access;
    private final String password;
    private long rateWindow;
    private int requests;

    @Autowired
    public GlassesReportAuth(GlassesAccess access, @Value("${astor.glasses.report-password:}") String password) {
        this.access = access;
        this.password = password;
    }

    GlassesAccess.Scope authorize(HttpServletRequest request) {
        if (password.isBlank() || password.length() < 12) {
            throw new GlassesFailure(503, "REPORT_UNAVAILABLE", "Report password is not configured");
        }
        checkRate();
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ") || header.length() > 1024) throw unauthorized();
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }
        byte[] expected = (USER + ":" + password).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, credentials.getBytes(StandardCharsets.UTF_8))) throw unauthorized();
        return access.configuredScope();
    }

    private synchronized void checkRate() {
        long window = System.currentTimeMillis() / 60000;
        if (window != rateWindow) { rateWindow = window; requests = 0; }
        if (++requests > RATE_LIMIT) throw new GlassesFailure(429, "RATE_LIMITED", "Request limit reached");
    }

    static GlassesFailure unauthorized() {
        return new GlassesFailure(401, "UNAUTHORIZED", "Server password required");
    }

    static String challenge() {
        return "Basic realm=\"Astor Glass\", charset=\"UTF-8\"";
    }
}
