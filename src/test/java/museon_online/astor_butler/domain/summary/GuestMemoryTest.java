package museon_online.astor_butler.domain.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuestMemoryTest {

    private static final Instant DAY = Instant.parse("2026-10-01T09:00:00Z");
    private static final String PHONE = "+79120000060";

    private SummaryTestDatabase database;
    private VisitSummaryRepository summaries;
    private GuestMemory memory;

    @BeforeEach
    void setUp() {
        database = SummaryTestDatabase.create();
        summaries = new VisitSummaryRepository(database.jdbc(), new ObjectMapper());
        memory = new GuestMemory(summaries);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void lastThreeVisitsByPhoneThenByChat() {
        for (int day = 0; day < 4; day++) {
            long visit = database.visit(PHONE, 100L, "AFTER", DAY.plus(day, ChronoUnit.DAYS));
            summaries.save(summary(visit, List.of("визит " + day)));
        }
        long noNotes = database.visit(PHONE, 100L, "AFTER", DAY.minus(1, ChronoUnit.DAYS));
        summaries.save(summary(noNotes, List.of()));
        long byChat = database.visit(null, 200L, "AFTER", DAY);
        summaries.save(summary(byChat, List.of("без лука", "тихий стол")));
        database.visit("+79120000099", 300L, "AFTER", DAY);

        assertThat(memory.of(PHONE, 200L)).containsExactly("визит 3", "визит 2", "визит 1");
        assertThat(memory.of(null, 200L)).containsExactly("без лука", "тихий стол");
        assertThat(memory.of("+79120000077", 200L)).containsExactly("без лука", "тихий стол");
        assertThat(memory.of("+79120000099", 300L)).isEmpty();
        assertThat(memory.of(null, null)).isEmpty();
    }

    private static VisitSummary summary(long visitId, List<String> nextTimeGuest) {
        return new VisitSummary(visitId, "стол", "стол", "пришёл", List.of(), nextTimeGuest, List.of(), null);
    }
}
