package museon_online.astor_butler.api.glasses.tasks;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** These are small JSON commands, not a media upload route. Bound even chunked request bodies. */
final class StaffBodyLimitFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken
                || !(request.getMethod().equals("POST") || request.getMethod().equals("PUT") || request.getMethod().equals("PATCH"))) {
            chain.doFilter(request, response); return;
        }
        byte[] bytes = request.getInputStream().readNBytes(65537);
        if (bytes.length > 65536) {
            response.setStatus(413); response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Staff JSON limit is 64 KiB\"}}");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous staff JSON only"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
}
