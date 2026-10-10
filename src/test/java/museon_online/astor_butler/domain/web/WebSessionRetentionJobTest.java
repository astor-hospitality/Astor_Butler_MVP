package museon_online.astor_butler.domain.web;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebSessionRetentionJobTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final WebSessionRetentionJob job = new WebSessionRetentionJob(jdbcTemplate);

    @Test
    void deletesMessagesAndSessionsOlderThanTheRetentionWindow() {
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "days", 30);
        when(jdbcTemplate.update(anyString(), eq(30))).thenReturn(7, 3);

        int removed = job.purgeNow();

        assertThat(removed).isEqualTo(10);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sql.capture(), eq(30));
        assertThat(sql.getAllValues().get(0)).startsWith("DELETE FROM web_messages WHERE created_at <");
        assertThat(sql.getAllValues().get(1)).startsWith("DELETE FROM web_sessions WHERE last_seen_at <");
    }

    @Test
    void doesNothingWhenDisabledOrWithoutAWindow() {
        ReflectionTestUtils.setField(job, "enabled", false);
        ReflectionTestUtils.setField(job, "days", 30);
        assertThat(job.purgeNow()).isZero();

        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "days", 0);
        assertThat(job.purgeNow()).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void scheduledRunSwallowsDatabaseErrors() {
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "days", 30);
        when(jdbcTemplate.update(anyString(), eq(30))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        job.purge();
    }
}
