package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;

import static museon_online.astor_butler.api.glasses.GlassesMessagePriority.ALERT;
import static museon_online.astor_butler.api.glasses.GlassesMessagePriority.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;

/** The rules of #186 on their own: what is an alert, what counts as a repeat, how the summary line reads. */
class GlassesMessagePriorityTest {
    private final GlassesMessagePriority rules = GlassesMessagePriority.enabled(6);

    @Test void theClassificationTable() {
        record Row(String text, String source, String requested, String expected) { }
        Row[] table = {
                // Words raise a message to an alert, whatever the source says.
                new Row("У стола 5 гость с аллергией на орехи", "RESTAURANT", null, ALERT),
                new Row("Аллергик за третьим столом", "RESTAURANT", "NORMAL", ALERT),
                new Row("VIP-гость сел за 12", "RESTAURANT", null, ALERT),
                new Row("вип гость у входа", "RESTAURANT", null, ALERT),
                new Row("Срочно подойди к бару", "RESTAURANT", null, ALERT),
                new Row("Задерживается подача на 7", "STAFF_TASK", "NORMAL", ALERT),
                new Row("Стоп. Менеджер остановил бронирование", "RESTAURANT", null, ALERT),
                // The word itself, not a word that starts the same way.
                new Row("Стопка лимонада на 4 стол", "RESTAURANT", null, NORMAL),
                new Row("Остановка подачи не планируется", "RESTAURANT", null, NORMAL),
                // The source alone makes an alert.
                new Row("Бронь 18:00 передана менеджеру", "ESCALATION", null, ALERT),
                new Row("Автоподтверждение выключено на смену", "KILL_SWITCH", null, ALERT),
                new Row("Бронь 18:00 передана менеджеру", "RESTAURANT", null, NORMAL),
                // A task's own priority passes through: HIGH is an alert, NORMAL is not.
                new Row("Принести воду на пятый стол", "STAFF_TASK", "HIGH", ALERT),
                new Row("Принести воду на пятый стол", "STAFF_TASK", "NORMAL", NORMAL),
                new Row("Принести воду на пятый стол", "STAFF_TASK", null, NORMAL),
                // The sender may ask for an alert outright; everything else is ordinary.
                new Row("Позвать к столу 9", "RESTAURANT", "ALERT", ALERT),
                new Row("Позвать к столу 9", "RESTAURANT", null, NORMAL),
                new Row("Счёт на столе 3", "RESTAURANT", null, NORMAL),
                new Row("Гость сел за 6", null, null, NORMAL),
        };
        for (Row row : table) {
            assertThat(rules.classify(row.text(), row.source(), row.requested()))
                    .as(row.source() + "/" + row.requested() + ": " + row.text()).isEqualTo(row.expected());
        }
    }

    @Test void alertWordsAreWholeWordsOrConfiguredStemsAndIgnoreCaseAndYo() {
        assertThat(rules.alertWord("АЛЛЕРГИЯ")).isTrue();
        assertThat(rules.alertWord("задёржка")).isTrue();
        assertThat(rules.alertWord("Vip")).isTrue();
        assertThat(rules.alertWord("стоп!")).isTrue();
        assertThat(rules.alertWord("стопка")).isFalse();
        assertThat(rules.alertWord("несрочно")).isFalse();
        assertThat(rules.alertWord("")).isFalse();
        assertThat(rules.alertWord(null)).isFalse();

        var custom = new GlassesMessagePriority(true, 6, " менеджер , счёт*,, ");
        assertThat(custom.alertWord("Позови менеджера")).isFalse();
        assertThat(custom.alertWord("Менеджер ждёт у бара")).isTrue();
        assertThat(custom.alertWord("Счет на столе 3")).isTrue();
        assertThat(custom.alertWord("аллергия")).as("the default list is replaced, not extended").isFalse();
    }

    @Test void sourcesAndRequestedPrioritiesAreCanonicalOrUnknown() {
        assertThat(GlassesMessagePriority.source(null)).isEqualTo("RESTAURANT");
        assertThat(GlassesMessagePriority.source(" staff-task ")).isEqualTo("STAFF_TASK");
        assertThat(GlassesMessagePriority.source("kill_switch")).isEqualTo("KILL_SWITCH");
        assertThat(GlassesMessagePriority.source("kitchen")).isNull();
        assertThat(GlassesMessagePriority.requested(null)).isNull();
        assertThat(GlassesMessagePriority.requested("alert")).isEqualTo(ALERT);
        assertThat(GlassesMessagePriority.requested("High")).isEqualTo("HIGH");
        assertThat(GlassesMessagePriority.requested("LOW")).isNull();
    }

    @Test void aRepeatIsTheSameTextUpToCaseSpacingYoAndTheFinalStop() {
        String key = GlassesMessagePriority.duplicateKey("Стол 5 просит счёт.");
        assertThat(GlassesMessagePriority.duplicateKey("  стол   5 просит счет ")).isEqualTo(key);
        assertThat(GlassesMessagePriority.duplicateKey("Стол 5 просит счёт!")).isEqualTo(key);
        assertThat(GlassesMessagePriority.duplicateKey("Стол 6 просит счёт.")).isNotEqualTo(key);
    }

    @Test void theSummaryLineCountsInRussian() {
        assertThat(GlassesMessagePriority.summary(1)).isEqualTo("Ещё 1 сообщение — на экране");
        assertThat(GlassesMessagePriority.summary(2)).isEqualTo("Ещё 2 сообщения — на экране");
        assertThat(GlassesMessagePriority.summary(4)).isEqualTo("Ещё 4 сообщения — на экране");
        assertThat(GlassesMessagePriority.summary(5)).isEqualTo("Ещё 5 сообщений — на экране");
        assertThat(GlassesMessagePriority.summary(11)).isEqualTo("Ещё 11 сообщений — на экране");
        assertThat(GlassesMessagePriority.summary(14)).isEqualTo("Ещё 14 сообщений — на экране");
        assertThat(GlassesMessagePriority.summary(21)).isEqualTo("Ещё 21 сообщение — на экране");
        assertThat(GlassesMessagePriority.summary(22)).isEqualTo("Ещё 22 сообщения — на экране");
    }

    @Test void theDefaultsAreOffSixPerMinuteAndTheRateNeverDropsBelowOne() {
        assertThat(GlassesMessagePriority.disabled().enabled()).isFalse();
        assertThat(new GlassesMessagePriority(false, 6, GlassesMessagePriority.DEFAULT_ALERT_WORDS).perMinute()).isEqualTo(6);
        assertThat(new GlassesMessagePriority(true, 0, "").perMinute()).isEqualTo(1);
        assertThat(GlassesMessagePriority.DEFAULT_PER_MINUTE).isEqualTo(6);
    }
}
