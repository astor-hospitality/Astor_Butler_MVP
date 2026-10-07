package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesTranscriptRelayTest {
    private static final String TOKEN = "unit-relay-token-not-real";
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private final HttpClient client = mock(HttpClient.class);
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("AERIS", "staff-1");
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private HttpResponse<Void> response(int status) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }

    @SuppressWarnings("unchecked")
    private org.mockito.stubbing.OngoingStubbing<HttpResponse<Void>> whenSent() throws Exception {
        return when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
    }

    private String body(HttpRequest request) {
        AtomicReference<String> text = new AtomicReference<>("");
        request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { text.set(text.get() + StandardCharsets.UTF_8.decode(item)); }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        }));
        return text.get();
    }

    @Test void sendsTheQuestionTheAnswerAndThePhotoWithTheSharedToken() throws Exception {
        HttpResponse<Void> ok = response(200);
        whenSent().thenReturn(ok);
        var relay = new GlassesTranscriptRelay(client, true, TOKEN, true);
        var context = new GlassesPhotoContext("80d26cf1-5139-4121-a4ca-dfb14aac225c", "BUSINESS_LUNCH_TWO", "PLACE_SETTINGS", 2);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2};

        assertThat(relay.send(scope, ID, "image", "Что проверить на столе?", "Видны два комплекта приборов.", context, jpeg)).isTrue();

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.headers().firstValue("X-Astor-Relay-Token")).hasValue(TOKEN);
        var json = mapper.readTree(body(request));
        assertThat(json.path("requestId").asText()).isEqualTo(ID);
        assertThat(json.path("kind").asText()).isEqualTo("image");
        assertThat(json.path("staff").asText()).isEqualTo("staff-1");
        assertThat(json.path("question").asText()).isEqualTo("Что проверить на столе?");
        assertThat(json.path("answer").asText()).isEqualTo("Видны два комплекта приборов.");
        assertThat(json.path("stageCode").asText()).isEqualTo("PLACE_SETTINGS");
        assertThat(json.path("photoMimeType").asText()).isEqualTo("image/jpeg");
        assertThat(json.path("photoBase64").asText()).isNotEmpty();
    }

    @Test void onlyThePhotoOfThisRequestTravels() throws Exception {
        HttpResponse<Void> ok = response(200);
        whenSent().thenReturn(ok);
        var relay = new GlassesTranscriptRelay(client, true, TOKEN, true);

        assertThat(relay.send(scope, ID, "audio", "Передай, что стол готов", "Передал бы, но я только подсказываю.", null, null)).isTrue();

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        var json = mapper.readTree(body(captor.getValue()));
        assertThat(json.has("photoBase64")).isFalse();
        assertThat(json.has("sessionId")).isFalse();
        assertThat(json.path("kind").asText()).isEqualTo("audio");
    }

    @Test void photosCanBeLeftOutOfTheChatWithoutLosingTheWords() throws Exception {
        HttpResponse<Void> ok = response(200);
        whenSent().thenReturn(ok);
        var relay = new GlassesTranscriptRelay(client, true, TOKEN, false);
        assertThat(relay.send(scope, ID, "image", "", "Видны приборы.", null, new byte[]{(byte) 0xFF, (byte) 0xD8})).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertThat(mapper.readTree(body(captor.getValue())).has("photoBase64")).isFalse();
    }

    @Test void staysQuietWhenItIsOffOrTheTokenIsTooShortAndNeverThrows() throws Exception {
        assertThat(GlassesTranscriptRelay.disabled().configured()).isFalse();
        assertThat(new GlassesTranscriptRelay(client, false, TOKEN, true).send(scope, ID, "text", "q", "a", null, null)).isFalse();
        assertThat(new GlassesTranscriptRelay(client, true, "short", true).send(scope, ID, "text", "q", "a", null, null)).isFalse();
        verifyNoInteractions(client);

        var relay = new GlassesTranscriptRelay(client, true, TOKEN, true);
        assertThat(relay.send(scope, ID, "text", "q", "   ", null, null)).isFalse();

        HttpResponse<Void> refused = response(500);
        whenSent().thenReturn(refused);
        assertThat(relay.send(scope, ID, "text", "q", "a", null, null)).isFalse();
        whenSent().thenThrow(new java.io.IOException("butler is away"));
        assertThat(relay.send(scope, ID, "text", "q", "a", null, null)).isFalse();
    }

    @Test void aHugeAnswerIsCutRatherThanRefused() throws Exception {
        HttpResponse<Void> ok = response(200);
        whenSent().thenReturn(ok);
        var relay = new GlassesTranscriptRelay(client, true, TOKEN, true);
        assertThat(relay.send(scope, UUID.fromString(ID).toString(), "text", "x".repeat(9000), "y".repeat(9000), null, null)).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        var json = mapper.readTree(body(captor.getValue()));
        assertThat(json.path("question").asText()).hasSize(GlassesTranscriptRelay.TEXT_LIMIT);
        assertThat(json.path("answer").asText()).hasSize(GlassesTranscriptRelay.TEXT_LIMIT);
    }
}
