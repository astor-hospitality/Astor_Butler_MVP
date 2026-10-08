package museon_online.astor_glasses_pilot;

import museon_online.astor_butler.api.glasses.GigaChatGlassesGateway;
import museon_online.astor_butler.api.glasses.YandexGlassesGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.speech.RecordingStubServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ASTOR_GLASSES_AI_PROVIDER picks the glasses' completions provider; gigachat answers through the GigaChat API. */
class GlassesPilotApplicationTest {
    private final GlassesPilotApplication.Config config = new GlassesPilotApplication.Config();

    @Test
    void theSettingSelectsTheProviderAndUnknownValuesFailStartup() {
        assertThat(config.gateway(new MockEnvironment())).isInstanceOf(YandexGlassesGateway.class);
        assertThat(config.gateway(new MockEnvironment().withProperty("ASTOR_GLASSES_AI_PROVIDER", "cloudru")))
                .isInstanceOf(YandexGlassesGateway.class);
        assertThat(config.gateway(new MockEnvironment().withProperty("ASTOR_GLASSES_AI_PROVIDER", "gigachat")))
                .isInstanceOf(GigaChatGlassesGateway.class);
        // The legacy variable still works, and the new one wins when both are set.
        assertThat(config.gateway(new MockEnvironment().withProperty("ASTOR_GLASSES_MODEL_PROVIDER", " GigaChat ")))
                .isInstanceOf(GigaChatGlassesGateway.class);
        assertThat(config.gateway(new MockEnvironment().withProperty("ASTOR_GLASSES_MODEL_PROVIDER", "gigachat")
                .withProperty("ASTOR_GLASSES_AI_PROVIDER", "yandex"))).isInstanceOf(YandexGlassesGateway.class);
        assertThatThrownBy(() -> config.gateway(new MockEnvironment().withProperty("ASTOR_GLASSES_AI_PROVIDER", "sber")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ASTOR_GLASSES_AI_PROVIDER")
                .hasMessageContaining("gigachat");
    }

    @Test
    void gigachatTextGoesToTheGigaChatHostWithItsOwnKey() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route("/api/v2/oauth", call -> RecordingStubServer.token("tok-glasses", 30 * 60_000));
            stub.route("/api/v1/chat/completions", call -> RecordingStubServer.Answer.json(200,
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Кухня закрывается в 23:00.\"}}]}"));
            var env = new MockEnvironment()
                    .withProperty("ASTOR_GLASSES_AI_PROVIDER", "gigachat")
                    .withProperty("ASTOR_GLASSES_GIGACHAT_AUTH_KEY", "Z2xhc3Nlcw==")
                    // Another provider's key in the same file is never sent to GigaChat.
                    .withProperty("ASTOR_GLASSES_CLOUDRU_API_KEY", "cloudru-key-fixture")
                    .withProperty("GIGACHAT_OAUTH_URL", stub.url("/api/v2/oauth"))
                    .withProperty("GIGACHAT_API_URL", stub.url("/api/v1"));

            var response = config.gateway(env).generateText(ModelTextRequest.of("До скольки кухня?", "t", "t", "t"));

            assertThat(response.text()).isEqualTo("Кухня закрывается в 23:00.");
            assertThat(response.model()).isEqualTo("GigaChat-2-Max");
            assertThat(stub.calls("/api/v2/oauth").getFirst().header("Authorization")).isEqualTo("Basic Z2xhc3Nlcw==");
            assertThat(stub.calls("/api/v1/chat/completions").getFirst().header("Authorization")).isEqualTo("Bearer tok-glasses");
            assertThat(stub.calls()).allSatisfy(call -> assertThat(call.text()).doesNotContain("cloudru-key-fixture"));
        }
    }
}
