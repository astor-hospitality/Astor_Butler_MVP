package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The real migration on an in-memory database: one row per reservation, taken by the primary key. */
class BookingMorningCheckRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);
    private static final Instant NOW = Instant.parse("2026-10-12T05:30:00Z");

    private final BookingMorningCheckRepository repository = new BookingMorningCheckRepository(BookingMorningCheckTestDatabase.create());

    @Test
    void aReservationIsClaimedOnceAndTheSecondClaimIsRefused() {
        assertThat(repository.claim(41L, 1001L, DAY)).isTrue();
        assertThat(repository.claim(41L, 1001L, DAY)).isFalse();
        assertThat(repository.claim(42L, 1002L, DAY)).isTrue();

        assertThat(repository.findByVisitDate(DAY)).extracting(BookingMorningCheck::reservationId).containsExactly(41L, 42L);
        assertThat(repository.findByVisitDate(DAY.plusDays(1))).isEmpty();
        assertThat(repository.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.chatId()).isEqualTo(1001L);
            assertThat(check.visitDate()).isEqualTo(DAY);
            assertThat(check.sent()).isFalse();
            assertThat(check.reconfirmed()).isFalse();
            assertThat(check.createdAt()).isNotNull();
        });
    }

    @Test
    void theOutcomeOfTheSendAndTheAnswerAreKeptOnTheRow() {
        repository.claim(41L, 1001L, DAY);
        repository.markFailed(41L, "x".repeat(300));
        assertThat(repository.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.sent()).isFalse();
            assertThat(check.deliveryError()).hasSize(160);
        });

        repository.markSent(41L, NOW);
        assertThat(repository.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.sentAt()).isEqualTo(NOW);
            assertThat(check.deliveryError()).isNull();
        });

        repository.answer(41L, BookingMorningCheck.ANSWER_MOVE, NOW.plusSeconds(60), false);
        assertThat(repository.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.answer()).isEqualTo("MOVE");
            assertThat(check.answeredAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(check.reconfirmed()).isFalse();
        });

        repository.answer(41L, BookingMorningCheck.ANSWER_KEEP, NOW.plusSeconds(120), true);
        assertThat(repository.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.answer()).isEqualTo("KEEP");
            assertThat(check.guestReconfirmedAt()).isEqualTo(NOW.plusSeconds(120));
        });
        assertThat(repository.find(99L)).isEmpty();
    }
}
