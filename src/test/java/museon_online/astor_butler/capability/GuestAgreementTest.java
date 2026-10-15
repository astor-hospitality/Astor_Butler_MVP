package museon_online.astor_butler.capability;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GuestAgreementTest {

    private static final GuestAgreement.Table AT_SEVEN = new GuestAgreement.Table(LocalDate.of(2026, 10, 20), LocalTime.of(19, 0), 2);

    @Test
    void plainAgreementIsAYes() {
        for (String words : List.of("да", "Да!", "Да, бронируйте", "ок", "Окей", "давайте", "Бронируй", "забронируйте пожалуйста",
                "подтверждаю", "Согласна", "подходит", "Хорошо", "идёт", "конечно", "ага", "yes", "+", "👍",
                "да, но на 20:00", "Да, на имя Анна")) {
            assertThat(GuestAgreement.saysYes(words)).as(words).isTrue();
        }
    }

    @Test
    void aQuestionANegationOrSilenceIsNotAYes() {
        for (String words : java.util.Arrays.asList(null, "", "  ", "Есть стол?", "Есть стол на завтра в 19 на двоих",
                "да?", "нет", "Нет, спасибо", "не надо", "да нет, не нужно", "подождите", "стоп", "отмена", "передумал",
                "а парковка есть?", "Хочу стол у окна", "на двоих к 13:00", "Обед сегодня")) {
            assertThat(GuestAgreement.saysYes(words)).as(String.valueOf(words)).isFalse();
        }
    }

    @Test
    void coversOnlyAProposedTableAfterAYes() {
        GuestAgreement yes = GuestAgreement.of("да", List.of(AT_SEVEN));
        GuestAgreement question = GuestAgreement.of("а у окна?", List.of(AT_SEVEN));
        GuestAgreement nothingProposed = GuestAgreement.of("да", List.of());

        assertThat(yes.covers(AT_SEVEN)).isTrue();
        assertThat(yes.covers(new GuestAgreement.Table(AT_SEVEN.date(), LocalTime.of(20, 0), 2))).isFalse();
        assertThat(yes.covers(new GuestAgreement.Table(AT_SEVEN.date(), AT_SEVEN.time(), 3))).isFalse();
        assertThat(yes.covers(new GuestAgreement.Table(AT_SEVEN.date().plusDays(1), AT_SEVEN.time(), 2))).isFalse();
        assertThat(question.covers(AT_SEVEN)).isFalse();
        assertThat(nothingProposed.covers(AT_SEVEN)).isFalse();
        assertThat(GuestAgreement.NONE.covers(AT_SEVEN)).isFalse();
    }

    @Test
    void aTableIsReadFromToolFacts() {
        assertThat(GuestAgreement.Table.of("2026-10-20", "19:00", 2)).contains(AT_SEVEN);
        assertThat(GuestAgreement.Table.of("2026-10-20", "19:00", "2")).contains(AT_SEVEN);
        // A result replayed from the idempotency store comes back from JSON.
        assertThat(GuestAgreement.Table.of("2026-10-20", "19:00", 2.0)).contains(AT_SEVEN);
        assertThat(GuestAgreement.Table.of("20.10.2026", "19:00", 2)).isEqualTo(Optional.empty());
        assertThat(GuestAgreement.Table.of("2026-10-20", null, 2)).isEmpty();
        assertThat(GuestAgreement.Table.of("2026-10-20", "19:00", 0)).isEmpty();
    }

    @Test
    void theRefusalNamesTheTableToPutToTheGuest() {
        CapabilityResult refusal = GuestAgreement.refusal(AT_SEVEN);

        assertThat(refusal.ok()).isFalse();
        assertThat(refusal.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(refusal.facts()).isEqualTo(Map.of("reason", GuestAgreement.NEEDS_GUEST_YES, "date", "2026-10-20",
                "time", "19:00", "partySize", 2));
        assertThat(GuestAgreement.Table.of(refusal.facts().get("date"), refusal.facts().get("time"),
                refusal.facts().get("partySize"))).contains(AT_SEVEN);
    }
}
