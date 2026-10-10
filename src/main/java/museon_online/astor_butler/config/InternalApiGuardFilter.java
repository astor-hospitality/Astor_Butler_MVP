package museon_online.astor_butler.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import museon_online.astor_butler.api.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guards the non-public part of the HTTP API with a shared internal token.
 *
 * <p>Butler has one Spring Security chain that permits everything, and the public edge used to proxy the whole
 * {@code /api/*} tree. That exposed booking, FSM and admin endpoints keyed by Telegram chat id to the Internet.
 * This filter is the in-process half of the fix (the edge allow-list in {@code infra/cloudru/edge/c3ag.caddy} and
 * {@code docker/nginx/nginx.conf.template} is the other half): paths in {@link #INTERNAL_PATHS} are served only when
 * the request carries {@value #HEADER} equal to {@code astor.security.internal-api-token}
 * ({@code ASTOR_INTERNAL_API_TOKEN}). Internal callers are the Concierge service and ops scripts on the docker
 * network; browsers never send the header.
 *
 * <p>Fail closed: with a blank or too short token every internal path answers 401 and one WARN names the variable
 * at startup. Public paths (web chat, glasses, staff portal with its own JWT chain, the glasses relay with its own
 * header) are not touched.
 *
 * <p>{@code POST /api/messages} is special: the website widget posts channel {@code WEB} anonymously and must keep
 * working, while {@code TELEGRAM} and {@code INTERNAL} drive the guest FSM as a stored chat id and need the token.
 * The filter peeks at the JSON body to read the channel and replays the bytes to the controller.
 *
 * <p>Registered inside the Spring Security chain (after the CORS filter) so the HTTP firewall has already rejected
 * path tricks such as {@code ..;/} or encoded slashes. Not a {@code @Bean}: Boot would otherwise register it a
 * second time as a plain servlet filter.
 */
public final class InternalApiGuardFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Astor-Internal-Token";
    public static final String PROPERTY = "astor.security.internal-api-token";
    public static final String ENV_VAR = "ASTOR_INTERNAL_API_TOKEN";
    static final int MIN_TOKEN_LENGTH = 16;
    static final int MAX_HEADER_LENGTH = 512;
    /** Largest anonymous /api/messages body the filter will buffer to read the channel; above it the request is 413. */
    static final int MAX_PEEK_BYTES = 256 * 1024;

    /**
     * Internal-only paths: require {@value #HEADER}. Keep in sync with the edge allow-list (c3ag.caddy, nginx
     * template) and docs/operations/PUBLIC_API_GUARD.md.
     */
    static final List<String> INTERNAL_PATHS = List.of(
            "/api/bookings/**",   // reservations keyed by chat id, hostess confirm/reject
            "/api/fsm/**",        // raw FSM state reads and writes
            "/api/admin/**",      // admin tooling (the staff portal subtree is excepted below)
            "/api/internal/**",   // service-to-service (the glasses relay is excepted below)
            "/api/concierge/**",  // guest service requests listed by chat id
            "/actuator/**"        // env, loggers, threaddump (health and prometheus are excepted below)
    );

    /**
     * Carve-outs inside the internal prefixes that stay reachable without the token because they carry their own
     * authentication or must be open for liveness checks.
     */
    static final List<String> PUBLIC_EXCEPTIONS = List.of(
            "/api/admin/staff/**",             // staff portal: own JWT chain, StaffPortalConfiguration (@Order(1))
            "/api/admin/staff-tasks/**",       // staff portal: same chain
            "/api/internal/glasses/transcript", // glasses relay: own X-Astor-Relay-Token, GlassesTranscriptController
            "/actuator/health",                // compose / edge liveness
            "/actuator/health/**",
            "/actuator/prometheus"             // Prometheus 2.x scrape via api-gateway; cannot send custom headers
    );

    /** The message gateway: anonymous only for channel WEB (or no channel, which the controller treats as WEB). */
    static final String MESSAGE_GATEWAY = "/api/messages";
    static final String WEB_CHANNEL = "WEB";

    private static final Logger log = LoggerFactory.getLogger(InternalApiGuardFilter.class);

    private final byte[] token;
    private final List<RequestMatcher> internal;
    private final List<RequestMatcher> exceptions;
    private final RequestMatcher messageGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public InternalApiGuardFilter(String configuredToken) {
        String value = configuredToken == null ? "" : configuredToken.trim();
        if (value.isEmpty()) {
            log.warn("{} ({}) is not set: internal API paths {} answer 401 until it is configured",
                    ENV_VAR, PROPERTY, INTERNAL_PATHS);
            this.token = null;
        } else if (value.length() < MIN_TOKEN_LENGTH) {
            log.warn("{} ({}) is shorter than {} characters and is ignored: internal API paths {} answer 401",
                    ENV_VAR, PROPERTY, MIN_TOKEN_LENGTH, INTERNAL_PATHS);
            this.token = null;
        } else {
            this.token = value.getBytes(StandardCharsets.UTF_8);
        }
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        this.internal = INTERNAL_PATHS.stream().map(paths::matcher).map(RequestMatcher.class::cast).toList();
        this.exceptions = PUBLIC_EXCEPTIONS.stream().map(paths::matcher).map(RequestMatcher.class::cast).toList();
        this.messageGateway = paths.matcher(MESSAGE_GATEWAY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(exceptions, request)) {
            chain.doFilter(request, response);
            return;
        }
        if (matches(internal, request)) {
            if (presented(request) == Presented.VALID) {
                chain.doFilter(request, response);
            } else {
                deny(request, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                        "This path is internal and requires " + HEADER, "INTERNAL_TOKEN_REQUIRED", Map.of());
            }
            return;
        }
        if (messageGateway.matches(request)) {
            guardMessageGateway(request, response, chain);
            return;
        }
        chain.doFilter(request, response);
    }

    private void guardMessageGateway(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (presented(request)) {
            case VALID -> {
                chain.doFilter(request, response);
                return;
            }
            case INVALID -> {
                // A caller that claims to be internal must prove it, whatever the channel.
                deny(request, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                        HEADER + " is not valid", "INTERNAL_TOKEN_REQUIRED", Map.of());
                return;
            }
            case ABSENT -> { }
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response); // no body to inspect; Spring MVC answers 405
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > MAX_PEEK_BYTES) {
            deny(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.BAD_REQUEST,
                    "Anonymous message body is too large", "PAYLOAD_TOO_LARGE", Map.of("maxBytes", MAX_PEEK_BYTES));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_PEEK_BYTES + 1);
        if (body.length > MAX_PEEK_BYTES) {
            deny(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.BAD_REQUEST,
                    "Anonymous message body is too large", "PAYLOAD_TOO_LARGE", Map.of("maxBytes", MAX_PEEK_BYTES));
            return;
        }
        String channel = channel(body);
        if (channel != null && !WEB_CHANNEL.equals(channel)) {
            deny(request, response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN,
                    "Channel " + channel + " on " + MESSAGE_GATEWAY + " is internal and requires " + HEADER,
                    "INTERNAL_CHANNEL_REQUIRES_TOKEN", Map.of("channel", channel));
            return;
        }
        chain.doFilter(new ReplayedBodyRequest(request, body), response);
    }

    /**
     * Channel named in the JSON body, upper-cased the way the controller does, or null when the body is not a JSON
     * object or names no channel. Malformed bodies are left to the controller, which answers 400 itself; they
     * cannot select a non-web channel.
     */
    private String channel(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode channel = root.get("channel");
            if (channel == null || channel.isNull()) {
                return null;
            }
            String value = channel.asText("").trim();
            return value.isEmpty() ? null : value.toUpperCase();
        } catch (IOException e) {
            return null;
        }
    }

    private enum Presented { ABSENT, INVALID, VALID }

    private Presented presented(HttpServletRequest request) {
        String presented = request.getHeader(HEADER);
        if (presented == null) {
            return Presented.ABSENT;
        }
        if (token == null || presented.isBlank() || presented.length() > MAX_HEADER_LENGTH) {
            return Presented.INVALID;
        }
        return MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))
                ? Presented.VALID : Presented.INVALID;
    }

    private static boolean matches(List<RequestMatcher> matchers, HttpServletRequest request) {
        for (RequestMatcher matcher : matchers) {
            if (matcher.matches(request)) {
                return true;
            }
        }
        return false;
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, int status, ErrorCode code,
                      String message, String reason, Map<String, Object> extra) throws IOException {
        log.warn("Rejected {} {} from {}: {} ({})", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr(), reason, status);
        String traceId = request.getHeader("X-Request-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("reason", reason);
        // Same shape as ApiErrorResponse (GlobalApiExceptionHandler); written by hand because this plain mapper has
        // no JSR-310 module and the filter must not depend on the MVC converter chain.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message);
        body.put("traceId", traceId);
        body.put("details", details);
        body.put("timestamp", Instant.now().toString());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    /** Hands the already-read body back to the controller. */
    private static final class ReplayedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        ReplayedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return bytes.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return bytes.read(); }
                @Override public int read(byte[] b, int off, int len) { return bytes.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            var charset = encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() { return body.length; }

        @Override
        public long getContentLengthLong() { return body.length; }
    }
}
