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
       what Mockito calls UnfinishedStubbing. Mockito cannot infer the body type of send(), so the
       matcher names it. */
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

    private HttpRequest sent() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), org.mockito.ArgumentMatchers.<BodyHandler<byte[]>>any());
        return captor.getValue();
    }

    @Test void speaksAsTheDocumentedV1ContractExpects() throws Exception {
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, 1, 2};
        HttpResponse<byte[]> ok = response(200, mp3);
        whenSent().thenReturn(ok);
        var speech = new GlassesSpeech(client, true, "filipp");

        assertThat(speech.synthesize("  Стол пять ждёт счёт.  ")).isEqualTo(mp3);
        assertThat(speech.ready()).isTrue();
        assertThat(speech.voiceName()).isEqualTo("filipp");

        HttpRequest request = sent();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize");
        assertThat(request.headers().firstValue("Authorization")).hasValue("Api-Key unit-key-not-a-real-credential");
        assertThat(request.headers().firstValue("Content-Type")).hasValue("application/x-www-form-urlencoded");
        String form = sentForm(request);
        assertThat(form).startsWith("text=").contains("&lang=ru-RU").contains("&voice=filipp").contains("&speed=0.95").contains("&format=mp3");
        // A service-account key carries its own folder; sending folderId with it is an error in v1.
        assertThat(form).doesNotContain("folderId");
        // v1 knows `emotion`, not `role`, and filipp has no emotions at all.
        assertThat(form).doesNotContain("role=").doesNotContain("emotion=");
        assertThat(form).doesNotContain("++"); // trimmed before it leaves: a double space would encode as ++
    }

    @Test void anEmotionIsSentOnlyForAVoiceThatHasIt() throws Exception {
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB};
        HttpResponse<byte[]> ok = response(200, mp3);
        whenSent().thenReturn(ok);
        var ermil = new GlassesSpeech(client, true, "ermil", "good");
        assertThat(ermil.misconfiguration()).isNull();
        assertThat(ermil.synthesize("Добрый день.")).isEqualTo(mp3);
        assertThat(sentForm(sent())).contains("&voice=ermil").contains("&emotion=good");

        // filipp has no emotions: asking for one is a misconfiguration, said once, and speech stays off.
        var wrong = new GlassesSpeech(client, true, "filipp", "good");
        assertThat(wrong.configured()).isFalse();
        assertThat(wrong.misconfiguration()).contains("filipp").contains("good");
        assertThat(wrong.synthesize("Строка")).isNull();
    }

    @Test void onlyVoicesServedByApiV1AreAccepted() {
        for (String voice : GlassesSpeech.MALE_VOICES) {
            assertThat(new GlassesSpeech(client, true, voice).configured()).as(voice).isTrue();
        }
        assertThat(new GlassesSpeech(client, true, "Filipp").configured()).isTrue(); // case does not matter
        for (String voice : new String[]{"alexander", "kirill", "anton", "", "siri"}) {
            var speech = new GlassesSpeech(client, true, voice);
            assertThat(speech.configured()).as(voice).isFalse();
            assertThat(speech.misconfiguration()).isNotNull();
        }
        verifyNoInteractions(client);
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
