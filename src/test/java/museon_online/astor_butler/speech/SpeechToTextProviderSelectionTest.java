package museon_online.astor_butler.speech;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechToTextProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SpeechToTextProviderCheck.class, ExternalCommandSpeechToTextService.class,
                    CloudRuWhisperSpeechToTextService.class, YandexSpeechKitSpeechToTextService.class);

    @Test
    void cloudRuIsTheDefaultAdapter() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SpeechToTextService.class);
            assertThat(context.getBean(SpeechToTextService.class)).isInstanceOf(CloudRuWhisperSpeechToTextService.class);
            assertThat(context.getBean(SpeechToTextProviderCheck.class).provider()).isEqualTo(SpeechToTextProvider.CLOUDRU);
        });
        runner.withPropertyValues("astor.speech-to-text.provider=cloudru").run(context ->
                assertThat(context.getBean(SpeechToTextService.class)).isInstanceOf(CloudRuWhisperSpeechToTextService.class));
    }

    @Test
    void localKeepsTheSubprocessAdapterForRollback() {
        runner.withPropertyValues("astor.speech-to-text.provider=local").run(context -> {
            assertThat(context).hasSingleBean(SpeechToTextService.class);
            assertThat(context.getBean(SpeechToTextService.class)).isInstanceOf(ExternalCommandSpeechToTextService.class);
            assertThat(context.getBean(SpeechToTextProviderCheck.class).provider()).isEqualTo(SpeechToTextProvider.LOCAL);
        });
    }

    @Test
    void yandexSelectsSpeechKitWithTheVoiceKey() {
        runner.withPropertyValues("astor.speech-to-text.provider=yandex", "astor.speech-to-text.yandex.api-key=unit-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(SpeechToTextService.class);
                    assertThat(context.getBean(SpeechToTextService.class)).isInstanceOf(YandexSpeechKitSpeechToTextService.class);
                    assertThat(context.getBean(SpeechToTextProviderCheck.class).provider()).isEqualTo(SpeechToTextProvider.YANDEX);
                });
    }

    @Test
    void unknownProviderFailsStartupNamingTheVariable() {
        runner.withPropertyValues("astor.speech-to-text.provider=salute").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("ASTOR_STT_PROVIDER")
                    .hasMessageContaining("salute");
        });
    }

    @Test
    void parseIsCaseInsensitiveAndNamesTheVariable() {
        assertThat(SpeechToTextProvider.parse(" CloudRu ", "X")).isEqualTo(SpeechToTextProvider.CLOUDRU);
        assertThat(SpeechToTextProvider.parse("local", "X")).isEqualTo(SpeechToTextProvider.LOCAL);
        assertThatThrownBy(() -> SpeechToTextProvider.parse("", "ASTOR_GLASSES_STT_PROVIDER"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ASTOR_GLASSES_STT_PROVIDER");
    }
}
