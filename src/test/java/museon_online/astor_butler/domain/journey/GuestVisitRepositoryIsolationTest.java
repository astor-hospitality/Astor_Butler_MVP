package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger is written from inside reservation transactions. On PostgreSQL a failed statement aborts the whole
 * transaction, so the ledger must run in its own: the reservation's fate and the ledger's never depend on each other.
 */
class GuestVisitRepositoryIsolationTest {

    @EnableTransactionManagement
    static class Config {
    }

    private JourneyTestDatabase database;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        context = new AnnotationConfigApplicationContext();
        context.register(Config.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(database.dataSource()));
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(database.dataSource()));
        context.registerBean(GuestVisitRepository.class, () -> new GuestVisitRepository(context.getBean(JdbcTemplate.class)));
        context.refresh();
    }

    @AfterEach
    void tearDown() throws Exception {
        context.close();
        database.close();
    }

    @Test
    void ledgerWritesSurviveARolledBackReservation() {
        GuestVisitRepository visits = context.getBean(GuestVisitRepository.class);
        TransactionTemplate reservation = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        reservation.executeWithoutResult(status -> {
            visits.open(42L, 421L, "+79120000000", "AERIS", Instant.parse("2026-10-14T08:00:00Z"));
            status.setRollbackOnly();
        });

        assertThat(visits.findByReservation(42L)).isPresent();
    }

    @Test
    void openingTheSameVisitTwiceReturnsTheFirst() {
        GuestVisitRepository visits = context.getBean(GuestVisitRepository.class);

        GuestVisit first = visits.open(42L, 421L, null, "AERIS", null);
        GuestVisit second = visits.open(42L, 999L, null, "AERIS", null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.chatId()).isEqualTo(421L);
    }
}
