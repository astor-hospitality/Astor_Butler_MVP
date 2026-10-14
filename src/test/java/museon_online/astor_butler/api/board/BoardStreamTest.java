package museon_online.astor_butler.api.board;

import museon_online.astor_butler.domain.ledger.VisitTurnAppended;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardStreamTest {

    private final BoardStream stream = new BoardStream();

    @AfterEach
    void tearDown() {
        stream.close();
    }

    @Test
    void sessionTurnsWithoutAVisitAreSkipped() {
        stream.open();

        assertThatCode(() -> {
            stream.onTurn(new VisitTurnAppended(null));
            stream.onTurn(new VisitTurnAppended(7L));
        }).doesNotThrowAnyException();
        assertThat(stream.clients()).isEqualTo(1);
    }

    @Test
    void streamsAreCapped() {
        for (int i = 0; i < BoardStream.MAX_CLIENTS; i++) {
            stream.open();
        }
        assertThatThrownBy(stream::open).isInstanceOf(ResponseStatusException.class);
    }
}
