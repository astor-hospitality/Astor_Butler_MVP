package museon_online.astor_butler.domain.summary;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLessonRepositoryTest {

    private SummaryTestDatabase database;
    private AgentLessonRepository lessons;
    private long visit;

    @BeforeEach
    void setUp() {
        database = SummaryTestDatabase.create();
        lessons = new AgentLessonRepository(database.jdbc());
        visit = database.visit("+79120000060", 100L, "AFTER", Instant.parse("2026-10-15T09:00:00Z"));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void onlyAcceptedLessonsReachTheAgents() {
        assertThat(lessons.propose(visit, "BUTLER", "не переспрашивать время")).isTrue();
        assertThat(lessons.propose(visit, "BUTLER", "не переспрашивать время")).isFalse();
        lessons.propose(visit, "BUTLER", "предлагать десерт");
        lessons.propose(visit, "CONCIERGE", "называть столы у окна");
        assertThat(lessons.accepted("BUTLER")).isEmpty();
        assertThat(lessons.accepted("CONCIERGE")).isEmpty();

        long time = idOf("не переспрашивать время");
        long dessert = idOf("предлагать десерт");
        long window = idOf("называть столы у окна");
        assertThat(lessons.accept(time, "не переспрашивать время, если гость его назвал")).isTrue();
        assertThat(lessons.reject(dessert)).isTrue();
        assertThat(lessons.accept(window, null)).isTrue();
        assertThat(lessons.accept(9_999L, "нет такого")).isFalse();

        assertThat(lessons.accepted("BUTLER")).containsExactly("не переспрашивать время, если гость его назвал");
        assertThat(lessons.accepted("CONCIERGE")).containsExactly("называть столы у окна");
        assertThat(lessons.withStatus(AgentLesson.Status.PROPOSED)).isEmpty();
        assertThat(lessons.withStatus(AgentLesson.Status.REJECTED)).extracting(AgentLesson::text).containsExactly("предлагать десерт");
        assertThat(lessons.find(time).orElseThrow().decidedAt()).isNotNull();

        lessons.addAccepted(visit, "BUTLER", "гость любит стол у окна");
        assertThat(lessons.accepted("BUTLER")).contains("гость любит стол у окна");
        assertThatThrownBy(() -> lessons.propose(visit, "STAFF", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    private long idOf(String text) {
        return lessons.withStatus(AgentLesson.Status.PROPOSED).stream().filter(lesson -> lesson.text().equals(text))
                .findFirst().orElseThrow().id();
    }
}
