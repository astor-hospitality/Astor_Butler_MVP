package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class GlassesVoiceTasksTest {
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("AERIS", "manager-1");
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final GlassesStaffTaskRelay relay = mock(GlassesStaffTaskRelay.class);

    private GlassesVoiceTasks tasks(boolean enabled) {
        when(relay.configured()).thenReturn(enabled);
        return new GlassesVoiceTasks(gateway, relay, enabled, GlassesVoiceTasks.DEFAULT_TRIGGERS);
    }

    private GlassesVoiceTasks.Receipt receipt(String assignee, String staffId, boolean self, String table) {
        return new GlassesVoiceTasks.Receipt("task-1", "Принести воду", assignee, staffId, self,
                "Принести воду на пятый стол", table, "NORMAL", "ASSIGNED");
    }

    private static ModelTextResponse text(String text) {
        return ModelTextResponse.text(text, "test", "test", Duration.ZERO);
    }

    /* ---------- trigger ---------- */

    @ParameterizedTest
    @ValueSource(strings = {"Задача: Анне принести воду на пятый стол", "задача, Анна, стол 5",
            "Поручение для Ильи: убрать седьмой стол", "Поручи Анне встретить гостей", "Передай Илье, что стол 3 ждёт счёт",
            "  «Задача» — проверить сервировку", "ЗАДАЧА!!! срочно воду на второй"})
    void aSpokenTriggerWordAtTheStartMakesATask(String text) {
        assertThat(tasks(true).isTask(null, text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Какие задачи на сегодня?", "Расскажи про поручения", "Анна, задача на пятый стол",
            "Поручения на смену", "Передайте Анне воду", "Что по бизнес-ланчу?", "", "   "})
    void otherQuestionsStayInformational(String text) {
        assertThat(tasks(true).isTask(null, text)).as(text).isFalse();
        assertThat(tasks(true).isTask("assist", text)).as(text).isFalse();
    }

    @Test void theExplicitIntentWinsOverTheWords() {
        assertThat(tasks(true).isTask("task", "Принести воду на пятый стол")).isTrue();
        // An explicit assist intent is a question even when it starts like an order.
        assertThat(tasks(true).isTask("assist", "Задача: принести воду")).isFalse();
    }

    @Test void nothingIsATaskWhileTheFeatureIsOffExceptAnExplicitIntentWhichIsRefused() {
        var off = tasks(false);
        assertThat(off.isTask(null, "Задача: принести воду")).isFalse();
        assertThatThrownBy(() -> off.isTask("task", "Принести воду"))
                .isInstanceOfSatisfying(GlassesFailure.class, failure -> {
                    assertThat(failure.status).isEqualTo(503);
                    assertThat(failure.code).isEqualTo("TASK_UNAVAILABLE");
                });
        assertThat(GlassesVoiceTasks.disabled().isTask(null, "Задача: принести воду")).isFalse();
    }

    @Test void triggerWordsAreConfigurable() {
        var custom = new GlassesVoiceTasks(gateway, relay, true, " заказ , Астор запиши ");
        when(relay.configured()).thenReturn(true);
        assertThat(custom.isTask(null, "Заказ: воду на пятый")).isTrue();
        assertThat(custom.isTask(null, "Астор запиши Анне воду")).isTrue();
        assertThat(custom.isTask(null, "Задача: воду на пятый")).isFalse();
    }

    /* ---------- parse ---------- */

    @Test void theModelAnswerBecomesTheDraft() {
        when(gateway.generateText(any())).thenReturn(text("{\"assignee\":\"Анна\",\"tableCode\":5,"
                + "\"title\":\"Принести воду\",\"instruction\":\"Принести воду на пятый стол\",\"priority\":\"high\"}"));
        var draft = tasks(true).parse("Задача: Анне срочно принести воду на пятый стол");
        assertThat(draft).isEqualTo(new GlassesVoiceTasks.Draft("Анна", "5", "Принести воду",
                "Принести воду на пятый стол", "HIGH"));
        verify(gateway).generateText(argThat(request -> request.scenario().equals("GLASSES_STAFF_TASK")
                && request.prompt().contains("Задача: Анне срочно принести воду на пятый стол")));
    }

    @Test void jsonWrappedInProseOrFencesStillParses() {
        when(gateway.generateText(any())).thenReturn(text("Вот результат:\n```json\n{\"assignee\":null,\"tableCode\":null,"
                + "\"title\":\"Проверить сервировку\",\"instruction\":\"Проверить сервировку всех столов\",\"priority\":\"NORMAL\"}\n```"));
        var draft = tasks(true).parse("Поручение: проверить сервировку всех столов");
        assertThat(draft.assignee()).isNull();
        assertThat(draft.tableCode()).isNull();
        assertThat(draft.title()).isEqualTo("Проверить сервировку");
        assertThat(draft.priority()).isEqualTo("NORMAL");
    }

    @Test void aLongTitleFromTheModelIsCutAndAnUnknownPriorityIsNormal() {
        when(gateway.generateText(any())).thenReturn(text("{\"title\":\"" + "очень ".repeat(20)
                + "длинно\",\"instruction\":\"x\",\"priority\":\"URGENT\"}"));
        var draft = tasks(true).parse("Задача: что-то длинное");
        assertThat(draft.title().length()).isLessThanOrEqualTo(GlassesVoiceTasks.TITLE_LIMIT);
        assertThat(draft.priority()).isEqualTo("NORMAL");
    }

    @Test void anythingTheModelGetsWrongFallsBackToTheWordsThemselves() {
        var tasks = tasks(true);
        var answers = new ModelTextResponse[]{text("Не могу разобрать."), text("{\"title\": "), text("{\"title\":\"  \"}"),
                text("[1,2]"), new ModelTextResponse("fallback", "test", "test", null, Duration.ZERO, true, Map.of()), null};
        for (var answer : answers) {
            reset(gateway);
            if (answer == null) when(gateway.generateText(any())).thenThrow(new RuntimeException("provider down"));
            else when(gateway.generateText(any())).thenReturn(answer);
            var draft = tasks.parse("Задача: Анне принести воду на пятый стол. Срочно, гости ждут");
            assertThat(draft).as(String.valueOf(answer)).isEqualTo(new GlassesVoiceTasks.Draft(null, null,
                    "Анне принести воду на пятый стол", "Анне принести воду на пятый стол. Срочно, гости ждут", "NORMAL"));
        }
    }

    @Test void theFallbackTitleIsTheFirstSentenceCutToSixtyCharacters() {
        String spoken = "Поручение " + "проверить сервировку, ".repeat(6) + "и доложить. Потом ещё что-то";
        var draft = GlassesVoiceTasks.fallback(spoken);
        assertThat(draft.title().length()).isLessThanOrEqualTo(GlassesVoiceTasks.TITLE_LIMIT);
        assertThat(draft.title()).startsWith("проверить сервировку");
        assertThat(draft.instruction()).startsWith("проверить сервировку").endsWith("Потом ещё что-то");
        // Only the trigger word itself: the instruction is then the whole utterance, not an empty one.
        assertThat(GlassesVoiceTasks.fallback("Задача").instruction()).isEqualTo("Задача");
        assertThat(GlassesVoiceTasks.fallback("Задача").title()).isEqualTo("Задача");
        assertThat(GlassesVoiceTasks.fallback("   ")).isEqualTo(new GlassesVoiceTasks.Draft(null, null, "Поручение", "Поручение", "NORMAL"));
    }

    /* ---------- create and confirm ---------- */

    @Test void createsThroughTheRelayWithTheRequestIdAsTheEventAndRemembersTheReceipt() {
        when(gateway.generateText(any())).thenReturn(text("{\"assignee\":\"Анна\",\"tableCode\":\"5\","
                + "\"title\":\"Принести воду\",\"instruction\":\"Принести воду на пятый стол\",\"priority\":\"NORMAL\"}"));
        var expected = receipt("Анна", "anna", false, "5");
        when(relay.create(eq(scope), eq(ID), any())).thenReturn(expected);
        var tasks = tasks(true);

        var receipt = tasks.create(scope, ID, "Задача: Анне принести воду на пятый стол");

        assertThat(receipt).isEqualTo(expected);
        verify(relay).create(scope, ID, new GlassesVoiceTasks.Draft("Анна", "5", "Принести воду",
                "Принести воду на пятый стол", "NORMAL"));
        assertThat(tasks.receipt(scope, ID)).isEqualTo(expected);
        assertThat(tasks.receipt(new GlassesAccess.Scope("OTHER", "manager-1"), ID)).isNull();
        assertThat(tasks.receipt(scope, "00000000-0000-0000-0000-000000000000")).isNull();
    }

    @Test void aRetryWithTheSameRequestIdSendsTheSameEventIdAgain() {
        when(gateway.generateText(any())).thenReturn(text("{\"title\":\"Принести воду\",\"instruction\":\"Принести воду\"}"));
        when(relay.create(any(), any(), any())).thenReturn(receipt(null, "manager-1", true, null));
        var tasks = tasks(true);
        tasks.create(scope, ID, "Задача: принести воду");
        tasks.create(scope, ID, "Задача: принести воду");
        verify(relay, times(2)).create(eq(scope), eq(ID), any());
    }

    @Test void whenButlerCannotTakeTheTaskTheWearerHearsToRepeatAndNothingIsRemembered() {
        when(gateway.generateText(any())).thenReturn(text("{\"title\":\"Принести воду\",\"instruction\":\"Принести воду\"}"));
        when(relay.create(any(), any(), any())).thenThrow(new GlassesFailure(503, "TASK_UNAVAILABLE", "Не смог записать поручение, повторите"));
        var tasks = tasks(true);
        assertThatThrownBy(() -> tasks.create(scope, ID, "Задача: принести воду"))
                .isInstanceOfSatisfying(GlassesFailure.class, failure -> {
                    assertThat(failure.status).isEqualTo(503);
                    assertThat(failure.code).isEqualTo("TASK_UNAVAILABLE");
                    assertThat(failure.getMessage()).isEqualTo("Не смог записать поручение, повторите");
                });
        assertThat(tasks.receipt(scope, ID)).isNull();
    }

    @Test void theConfirmationIsShortAndSpoken() {
        assertThat(GlassesVoiceTasks.confirmation(receipt("Анна", "anna", false, "5")))
                .isEqualTo("Записал поручение для Анна: Принести воду. Стол 5.");
        assertThat(GlassesVoiceTasks.confirmation(receipt(null, "manager-1", true, "5")))
                .isEqualTo("Поручение записал на вас: Принести воду. Стол 5.");
        assertThat(GlassesVoiceTasks.confirmation(receipt("Илья", "ilya", false, null)))
                .isEqualTo("Записал поручение для Илья: Принести воду.");
        assertThat(GlassesVoiceTasks.confirmation(receipt(null, "manager-1", true, "")))
                .isEqualTo("Поручение записал на вас: Принести воду.");
    }
}
