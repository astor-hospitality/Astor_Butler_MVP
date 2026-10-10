package museon_online.astor_butler.i18n;

import museon_online.astor_butler.domain.feedback.FeedbackService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.scenario.FeedbackScenario;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The Spring wiring of the guest-language layer, with the defaults and switched on. */
class I18nConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(I18nConfig.Binding.class, I18nConfiguration.class);

    @Test
    void defaultsWireAnInactiveLayerWithoutPaidTranslation() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GuestTexts.class);
            assertThat(context.getBean(I18nConfig.class).enabled()).isFalse();
            assertThat(context.getBean(MachineTranslator.class).available()).isFalse();
            GuestTexts texts = context.getBean(GuestTexts.class);
            assertThat(texts.forGuest(english(), "feedback.button").text()).isEqualTo("Оставить отзыв");
        });
    }

    @Test
    void switchedOnTheLayerAnswersInTheGuestLanguage() {
        runner.withPropertyValues("astor.i18n.enabled=true", "astor.i18n.machine-translation.provider=yandex")
                .run(context -> {
                    assertThat(context.getBean(MachineTranslator.class)).isInstanceOf(NoOpMachineTranslator.class);
                    GuestTexts texts = context.getBean(GuestTexts.class);
                    assertThat(texts.forGuest(english(), "feedback.button").text()).isEqualTo("Leave feedback");
                });
    }

    @Test
    void feedbackScenarioIsBuiltWithTheSharedGuestTexts() {
        runner.withPropertyValues("astor.i18n.enabled=true")
                .withBean(FSMStorage.class, () -> Mockito.mock(FSMStorage.class))
                .withBean(FeedbackService.class, () -> Mockito.mock(FeedbackService.class))
                .withBean(FeedbackScenario.class)
                .run(context -> {
                    FeedbackScenario scenario = context.getBean(FeedbackScenario.class);
                    IncomingMessage button = IncomingMessage.telegram(5L, 5L, 1, 1, "Leave feedback", null, "Ann", null,
                            null, "en", false, "c");
                    assertThat(scenario.handle(button, BotState.READY_FOR_DIALOG, button.text()).text())
                            .startsWith("Please write your feedback");
                });
    }

    private static IncomingMessage english() {
        return IncomingMessage.telegram(5L, 5L, 1, 1, "Hello, I would like to leave feedback", null, "Ann", null, null,
                "en", false, "c");
    }
}
