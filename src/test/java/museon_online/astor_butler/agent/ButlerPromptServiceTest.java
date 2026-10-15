package museon_online.astor_butler.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** In a negotiation the Butler finds out what the restaurant needs from the guest for great service. */
class ButlerPromptServiceTest {

    private final String prompt = ButlerPrompt.system(List.of(), List.of(), null, LocalDate.of(2026, 10, 15));

    @Test
    void serviceIsAGoalAndTheButlerAsksForWhatMatters() {
        assertThat(prompt).contains("отличный сервис");
        assertThat(prompt).contains("повод", "аллергии", "стол");
        assertThat(prompt).contains("через Консьержа");
    }

    @Test
    void whatTheGuestSaidGoesIntoTheReservationWish() {
        assertThat(prompt).contains("reservation_create", "wish");
    }

    @Test
    void theButlerDoesNotInterrogateTheGuest() {
        assertThat(prompt).contains("не больше одного такого вопроса за ход");
    }
}
