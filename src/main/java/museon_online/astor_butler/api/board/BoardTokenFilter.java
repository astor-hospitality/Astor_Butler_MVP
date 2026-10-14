package museon_online.astor_butler.api.board;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Closes {@code /api/board/**}: every request carries {@value #HEADER} equal to {@code ASTOR_BOARD_TOKEN}; the live
 * stream alone may pass it as the query parameter {@value #QUERY_PARAM}, because a browser EventSource cannot set headers.
 * A token shorter than 16 characters closes everything. With the board off every board path answers 404. Other paths
 * are not touched. Registered by Boot as a plain servlet filter, so it runs after the Spring Security firewall.
 */
@Component
public class BoardTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Astor-Board-Token";
    static final String QUERY_PARAM = "t";
    static final String ENV_VAR = "ASTOR_BOARD_TOKEN";
    static final String PREFIX = "/api/board";
    static final String STREAM = "/api/board/v1/stream";
    static final int MIN_TOKEN_LENGTH = 16;
    private static final int MAX_PRESENTED_LENGTH = 512;

    private static final Logger log = LoggerFactory.getLogger(BoardTokenFilter.class);

    private final boolean enabled;
    private final byte[] token;

    public BoardTokenFilter(BoardProperties properties) {
        this.enabled = properties.isEnabled();
        String value = properties.getToken() == null ? "" : properties.getToken().trim();
        if (value.length() < MIN_TOKEN_LENGTH) {
            if (enabled) {
                log.warn("{} (astor.board.token) is blank or shorter than {} characters: every board path answers 401",
                        ENV_VAR, MIN_TOKEN_LENGTH);
            }
            this.token = null;
        } else {
            this.token = value.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            deny(response, HttpServletResponse.SC_NOT_FOUND, "board is off");
            return;
        }
        if (valid(presented(request))) {
            chain.doFilter(request, response);
            return;
        }
        deny(response, HttpServletResponse.SC_UNAUTHORIZED, "board token required");
    }

    private String presented(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null) {
            return header;
        }
        return STREAM.equals(path(request)) ? request.getParameter(QUERY_PARAM) : null;
    }

    private boolean valid(String presented) {
        return token != null && presented != null && !presented.isBlank() && presented.length() <= MAX_PRESENTED_LENGTH
                && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static void deny(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
