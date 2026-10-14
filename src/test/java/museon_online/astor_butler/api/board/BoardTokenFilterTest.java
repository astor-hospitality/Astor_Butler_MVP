package museon_online.astor_butler.api.board;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class BoardTokenFilterTest {

    private static final String TOKEN = "board-token-0123456789";

    @Test
    void rejectsWithoutOrWithWrongToken() throws Exception {
        BoardTokenFilter filter = filter(TOKEN);

        assertThat(run(filter, get("/api/board/v1/visits")).getStatus()).isEqualTo(401);
        MockHttpServletRequest wrong = get("/api/board/v1/visits");
        wrong.addHeader(BoardTokenFilter.HEADER, "board-token-WRONG-6789");
        assertThat(run(filter, wrong).getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsEverythingWhenTokenTooShort() throws Exception {
        BoardTokenFilter filter = filter("12345678");
        MockHttpServletRequest request = get("/api/board/v1/visits");
        request.addHeader(BoardTokenFilter.HEADER, "12345678");

        assertThat(run(filter, request).getStatus()).isEqualTo(401);
    }

    @Test
    void passesWithToken() throws Exception {
        BoardTokenFilter filter = filter(TOKEN);
        MockHttpServletRequest request = get("/api/board/v1/visits");
        request.addHeader(BoardTokenFilter.HEADER, TOKEN);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void queryTokenIsAcceptedOnlyOnTheStream() throws Exception {
        BoardTokenFilter filter = filter(TOKEN);
        MockHttpServletRequest stream = get("/api/board/v1/stream");
        stream.setParameter("t", TOKEN);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(stream, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();

        MockHttpServletRequest visits = get("/api/board/v1/visits");
        visits.setParameter("t", TOKEN);
        assertThat(run(filter, visits).getStatus()).isEqualTo(401);
    }

    @Test
    void percentEncodedBoardPathIsGuardedToo() throws Exception {
        MockHttpServletRequest request = get("/api/%62oard/v1/visits");
        request.setServletPath("/api/board/v1/visits");

        assertThat(run(filter(TOKEN), request).getStatus()).isEqualTo(401);
    }

    @Test
    void otherPathsAreNotTouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter(TOKEN).doFilter(get("/api/web/chat"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void boardOffAnswersNotFound() throws Exception {
        BoardProperties properties = new BoardProperties();
        properties.setToken(TOKEN);
        MockHttpServletRequest request = get("/api/board/v1/visits");
        request.addHeader(BoardTokenFilter.HEADER, TOKEN);

        assertThat(run(new BoardTokenFilter(properties), request).getStatus()).isEqualTo(404);
    }

    private static BoardTokenFilter filter(String token) {
        BoardProperties properties = new BoardProperties();
        properties.setEnabled(true);
        properties.setToken(token);
        return new BoardTokenFilter(properties);
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static MockHttpServletResponse run(BoardTokenFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        return response;
    }
}
