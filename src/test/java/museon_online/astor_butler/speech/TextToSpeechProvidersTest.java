package museon_online.astor_butler.speech;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider selection: one variable chooses the adapter, a misspelt one fails at startup. */
class TextToSpeechProvidersTest {

    private static final SpeechKitTextToSpeech.Settings YANDEX =
            new SpeechKitTextToSpeech.Settings(null, "yk", "folder", "filipp", null, 0.95);
    private static final SaluteSpeechTextToSpeech.Settings SALUTE =
            new SaluteSpeechTextToSpeech.Settings("sk", null, null, null, null, null, null, Duration.ofSeconds(5));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TextToSpeechConfig.class);

    @Test
    void selectsByNameCaseInsensitivelyAndRefusesAnythingElse() {
        assertThat(TextToSpeechProviders.select("salute", YANDEX, SALUTE)).isInstanceOf(SaluteSpeechTextToSpeech.class);
        assertThat(TextToSpeechProviders.select(" Yandex ", YANDEX, SALUTE)).isInstanceOf(SpeechKitTextToSpeech.class);
        assertThatThrownBy(() -> TextToSpeechProviders.select("speechkit", YANDEX, SALUTE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("yandex or salute");
        assertThatThrownBy(() -> TextToSpeechProviders.select("", YANDEX, SALUTE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TextToSpeechProviders.select(null, YANDEX, SALUTE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void saluteIsTheApplicationDefaultWithItsOwnDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TextToSpeech.class);
            TextToSpeech speech = context.getBean(TextToSpeech.class);
            assertThat(speech).isInstanceOf(SaluteSpeechTextToSpeech.class);
            assertThat(speech.voice()).isEqualTo("Nec_24000");
            assertThat(speech.mimeType()).isEqualTo("audio/wav");
            // No key yet: the bean exists, speech is simply unavailable.
            assertThat(speech.configured()).isFalse();
        });
    }

    @Test
    void saluteSettingsComeFromTheAstorTtsProperties() {
        contextRunner.withPropertyValues(
                "astor.tts.provider=salute",
                "astor.tts.salute.auth-key=unit-key",
                "astor.tts.salute.scope=SALUTE_SPEECH_B2B",
                "astor.tts.salute.voice=Bys_24000",
                "astor.tts.salute.format=opus",
                "astor.tts.salute.tts-url=https://speech.test/rest/v1/text:synthesize"
        ).run(context -> {
            SaluteSpeechTextToSpeech speech = context.getBean(SaluteSpeechTextToSpeech.class);
            assertThat(speech.configured()).isTrue();
            assertThat(speech.voice()).isEqualTo("Bys_24000");
            assertThat(speech.voiceGender()).isEqualTo("male");
            assertThat(speech.mimeType()).isEqualTo("audio/ogg");
            assertThat(speech.synthesisUri().toString())
                    .isEqualTo("https://speech.test/rest/v1/text:synthesize?format=opus&voice=Bys_24000");
        });
    }

    @Test
    void yandexIsSelectableForRollback() {
        contextRunner.withPropertyValues(
                "astor.tts.provider=yandex",
                "astor.tts.yandex.api-key=yk",
                "astor.tts.yandex.folder-id=folder",
                "astor.tts.yandex.voice=ermil"
        ).run(context -> {
            TextToSpeech speech = context.getBean(TextToSpeech.class);
            assertThat(speech).isInstanceOf(SpeechKitTextToSpeech.class);
            assertThat(speech.configured()).isTrue();
            assertThat(speech.voice()).isEqualTo("ermil");
            assertThat(speech.mimeType()).isEqualTo("audio/mpeg");
        });
    }

    @Test
    void anUnknownProviderFailsTheContext() {
        contextRunner.withPropertyValues("astor.tts.provider=speechkit").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("yandex or salute");
        });
    }
}
