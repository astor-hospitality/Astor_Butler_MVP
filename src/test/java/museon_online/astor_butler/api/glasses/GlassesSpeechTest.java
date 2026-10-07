package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesSpeechTest {
    private final HttpClient client = mock(HttpClient.class);

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /* Each response is built before the stubbing starts: a nested when() inside an unfinished one is
       what Mockito calls UnfinishedStubbing. */
    /** Mockito cannot infer the body type of send(), so the matcher names it. */
    private org.mockito.stubbing.OngoingStubbing<HttpResponse<byte[]>> whenSent() throws Exception {
        return when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<BodyHandler<byte[]>>any()));
    }

    private String sentForm(HttpRequest request) {
        AtomicReference<String> form = new AtomicReference<>("");
        request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer item) { form.set(form.get() + StandardCharsets.UTF_8.decode(item)); }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        }));
        return form.get();
    }

    @Test void asksForOneMalePremiumVoiceWithTheKeyInTheHeader() throws Exception {
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, 1, 2};
        HttpResponse<byte[]> ok = response(200, mp3);
        whenSent().thenReturn(ok);
        var speech = new GlassesSpeech(client, true, "filipp");

        assertThat(speech.synthesize("  Стол пять ждёт счёт.  ")).isEqualTo(mp3);
        assertThat(speech.ready()).isTrue();
        assertThat(speech.voiceName()).isEqualTo("filipp");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), org.mockito.ArgumentMatchers.<BodyHandler<byte[]>>any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize");
        assertThat(request.headers().firstValue("Authorization")).hasValue("Api-Key unit-key");
        String form = sentForm(request);
        assertThat(form).contains("voice=filipp").contains("lang=ru-RU").contains("format=mp3").contains("role=neutral").contains("speed=0.95");
        assertThat(form).doesNotContain("  "); // the line is trimmed before it leaves
    }

    @Test void saysNothingWhenItIsOffOrTheLineDoesNotFit() throws Exception {
        assertThat(new GlassesSpeech(client, false, "filipp").synthesize("Строка")).isNull();
        assertThat(GlassesSpeech.disabled().configured()).isFalse();
        var speech = new GlassesSpeech(client, true, "filipp");
        assertThat(speech.synthesize(null)).isNull();
        assertThat(speech.synthesize("   ")).isNull();
        assertThat(speech.synthesize("ё".repeat(GlassesSpeech.TEXT_LIMIT + 1))).isNull();
        verifyNoInteractions(client);
    }

    @Test void aProviderFailureLeavesTheTextToThePhoneWithoutLeakingAnything() throws Exception {
        HttpResponse<byte[]> rejected = response(401, new byte[]{1});
        HttpResponse<byte[]> empty = response(200, new byte[0]);
        HttpResponse<byte[]> huge = response(200, new byte[GlassesSpeech.AUDIO_LIMIT + 1]);
        whenSent().thenReturn(rejected);
        var speech = new GlassesSpeech(client, true, "filipp");
        assertThat(speech.synthesize("Строка")).isNull();
        assertThat(speech.ready()).isFalse();

        whenSent().thenReturn(empty);
        assertThat(speech.synthesize("Строка")).isNull();

        whenSent().thenReturn(huge);
        assertThat(speech.synthesize("Строка")).isNull();

        whenSent().thenThrow(new java.io.IOException("private tts key diagnostic"));
        assertThat(speech.synthesize("Строка")).isNull();
        assertThat(speech.ready()).isFalse();
    }
}
