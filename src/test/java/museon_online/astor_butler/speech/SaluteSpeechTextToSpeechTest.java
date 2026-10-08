package museon_online.astor_butler.speech;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SaluteSpeech contract against a local HTTP stub: what leaves for the OAuth gateway and the
 * synthesis endpoint, how the token is cached and renewed, and what the adapter does with every answer.
 */
class SaluteSpeechTextToSpeechTest {

    private static final String AUTH_KEY = "dW5pdC1jbGllbnQ6dW5pdC1zZWNyZXQ=";
    private static final byte[] WAV = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'};

    record Call(String path, String query, String method, String authorization, String contentType, String rqUid,
                String accept, String body) { }

    record Answer(int status, byte[] body) {
        static Answer ok(byte[] body) { return new Answer(200, body); }
        static Answer json(int status, String json) { return new Answer(status, json.getBytes(StandardCharsets.UTF_8)); }
    }

    private HttpServer server;
    private final List<Call> oauthCalls = new CopyOnWriteArrayList<>();
    private final List<Call> speechCalls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<Call, Answer>> oauth = new AtomicReference<>();
    private final AtomicReference<Function<Call, Answer>> speech = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v2/oauth", exchange -> handle(exchange, oauthCalls, oauth.get()));
        server.createContext("/rest/v1/text:synthesize", exchange -> handle(exchange, speechCalls, speech.get()));
        server.start();
        oauth.set(call -> Answer.json(200, token("tok-1", 30 * 60_000)));
        speech.set(call -> Answer.ok(WAV));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange, List<Call> calls, Function<Call, Answer> answerer) throws IOException {
        var headers = exchange.getRequestHeaders();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Call call = new Call(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                exchange.getRequestMethod(), headers.getFirst("Authorization"), headers.getFirst("Content-Type"),
                headers.getFirst("RqUID"), headers.getFirst("Accept"), body);
        calls.add(call);
        Answer answer = answerer.apply(call);
        exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer.body());
        }
    }

    private static String token(String value, long expiresInMs) {
        return "{\"access_token\":\"" + value + "\",\"expires_at\":" + (System.currentTimeMillis() + expiresInMs) + "}";
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SaluteSpeechTextToSpeech adapter(String authKey, String scope, String voice, String format) {
        return new SaluteSpeechTextToSpeech(HttpClient.newHttpClient(), new SaluteSpeechTextToSpeech.Settings(
                authKey, scope, voice, format, "", base() + "/api/v2/oauth", base() + "/rest/v1/text:synthesize",
                Duration.ofSeconds(5)));
    }

    @Test
    void firstLineObtainsATokenWithBasicKeyRqUidAndScopeThenSpeaksWithTheBearer() {
        SaluteSpeechTextToSpeech tts = adapter(AUTH_KEY, "SALUTE_SPEECH_B2B", "Bys_24000", "wav16");

        byte[] audio = tts.synthesize("Стол пять ждёт счёт.");

        assertThat(audio).isEqualTo(WAV);
        assertThat(oauthCalls).hasSize(1);
        Call auth = oauthCalls.getFirst();
        assertThat(auth.method()).isEqualTo("POST");
        assertThat(auth.authorization()).isEqualTo("Basic " + AUTH_KEY);
        assertThat(auth.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(auth.accept()).isEqualTo("application/json");
        assertThat(auth.rqUid()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(auth.body()).isEqualTo("scope=SALUTE_SPEECH_B2B");

        assertThat(speechCalls).hasSize(1);
        Call spoken = speechCalls.getFirst();
        assertThat(spoken.method()).isEqualTo("POST");
        assertThat(spoken.path()).isEqualTo("/rest/v1/text:synthesize");
        assertThat(spoken.query()).isEqualTo("format=wav16&voice=Bys_24000");
        assertThat(spoken.authorization()).isEqualTo("Bearer tok-1");
        assertThat(spoken.contentType()).isEqualTo("application/text");
        assertThat(spoken.rqUid()).isNull();
        assertThat(spoken.body()).isEqualTo("Стол пять ждёт счёт.");

        assertThat(tts.provider()).isEqualTo("salute");
        assertThat(tts.voice()).isEqualTo("Bys_24000");
        assertThat(tts.voiceGender()).isEqualTo("male");
        assertThat(tts.mimeType()).isEqualTo("audio/wav");
        assertThat(tts.configured()).isTrue();
    }

    @Test
    void theTokenIsReusedUntilItIsAboutToExpire() {
        AtomicInteger issued = new AtomicInteger();
        oauth.set(call -> Answer.json(200, token("tok-" + issued.incrementAndGet(), 30 * 60_000)));
        SaluteSpeechTextToSpeech tts = adapter(AUTH_KEY, "", "", "");

        tts.synthesize("Раз");
        tts.synthesize("Два");
        tts.synthesize("Три");

        assertThat(oauthCalls).hasSize(1);
        assertThat(speechCalls).extracting(Call::authorization).containsExactly("Bearer tok-1", "Bearer tok-1", "Bearer tok-1");
        assertThat(speechCalls.getFirst().query()).isEqualTo("format=wav16&voice=Nec_24000");
        assertThat(oauthCalls.getFirst().body()).isEqualTo("scope=SALUTE_SPEECH_PERS");
        assertThat(tts.voiceGender()).isEqualTo("female");
    }

    @Test
    void aTokenInsideTheRefreshMarginIsRenewedBeforeTheNextLine() {
        AtomicInteger issued = new AtomicInteger();
        // The first token has 10 seconds left: used once, then replaced before it can expire mid-call.
        oauth.set(call -> Answer.json(200, token("tok-" + issued.incrementAndGet(), issued.get() == 1 ? 10_000 : 30 * 60_000)));
        SaluteSpeechTextToSpeech tts = adapter(AUTH_KEY, "", "", "");

        tts.synthesize("Раз");
        tts.synthesize("Два");
        tts.synthesize("Три");

        assertThat(oauthCalls).hasSize(2);
        assertThat(speechCalls).extracting(Call::authorization).containsExactly("Bearer tok-1", "Bearer tok-2", "Bearer tok-2");
    }

    @Test
    void aRejectedTokenIsRenewedOnceAndASecondRejectionIsTheCallersProblem() {
        AtomicInteger issued = new AtomicInteger();
        oauth.set(call -> Answer.json(200, token("tok-" + issued.incrementAndGet(), 30 * 60_000)));
        speech.set(call -> "Bearer tok-1".equals(call.authorization())
                ? Answer.json(401, "{\"status\":401,\"message\":\"Token has expired\"}") : Answer.ok(WAV));
        SaluteSpeechTextToSpeech tts = adapter(AUTH_KEY, "", "", "");

        assertThat(tts.synthesize("Строка")).isEqualTo(WAV);
        assertThat(oauthCalls).hasSize(2);
        assertThat(speechCalls).extracting(Call::authorization).containsExactly("Bearer tok-1", "Bearer tok-2");

        speech.set(call -> Answer.json(401, "{\"status\":401}"));
        assertThatThrownBy(() -> tts.synthesize("Строка"))
                .isInstanceOf(TextToSpeechException.class)
                .hasMessageContaining("401")
                .satisfies(e -> assertThat(((TextToSpeechException) e).status()).isEqualTo(401));
        // One more token request, two more speech calls, and no infinite re-auth loop.
        assertThat(oauthCalls).hasSize(3);
        assertThat(speechCalls).hasSize(4);
    }

    @Test
    void ssmlGoesOutAsSsmlAndFormatsMapToTheirMimeTypes() {
        SaluteSpeechTextToSpeech tts = adapter(AUTH_KEY, "", "Tur_8000", "opus");

        tts.synthesize("<speak>Стол <break time=\"300ms\"/> пять</speak>");

        assertThat(speechCalls.getFirst().contentType()).isEqualTo("application/ssml");
        assertThat(speechCalls.getFirst().query()).isEqualTo("format=opus&voice=Tur_8000");
        assertThat(tts.mimeType()).isEqualTo("audio/ogg");
        assertThat(tts.voiceGender()).isEqualTo("male");
        assertThat(adapter(AUTH_KEY, "", "", "pcm16").mimeType()).isEqualTo("audio/x-pcm");
        assertThat(adapter(AUTH_KEY, "", "", "ALAW").mimeType()).isEqualTo("audio/x-alaw");
        assertThat(adapter(AUTH_KEY, "", "", "mp3").mimeType()).isEqualTo("application/octet-stream");
    }

    @Test
    void providerErrorsSurfaceByStatusAndNeverCarryTheKeyOrTheLine() {
        SaluteSpeechTextToSpeech tts = adapter(AUTH_KEY, "", "", "");
        String secretLine = "Гость за столом семь просил не называть его имя";

        speech.set(call -> Answer.json(400, "{\"status\":400,\"message\":\"bad voice\"}"));
        assertThatThrownBy(() -> tts.synthesize(secretLine)).isInstanceOf(TextToSpeechException.class)
                .hasMessageContaining("400").satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(AUTH_KEY).doesNotContain(secretLine).doesNotContain("tok-1");
                    assertThat(((TextToSpeechException) e).status()).isEqualTo(400);
                });

        speech.set(call -> Answer.json(500, "{\"status\":500}"));
        assertThatThrownBy(() -> tts.synthesize(secretLine)).isInstanceOf(TextToSpeechException.class)
                .hasMessageContaining("500");

        speech.set(call -> Answer.ok(new byte[0]));
        assertThatThrownBy(() -> tts.synthesize(secretLine)).isInstanceOf(TextToSpeechException.class)
                .hasMessageContaining("no audio");

        // The token was fine throughout: no re-auth for anything but 401.
        assertThat(oauthCalls).hasSize(1);
    }

    @Test
    void oauthFailuresNameTheProblemWithoutTheKey() {
        oauth.set(call -> Answer.json(401, "{\"code\":1,\"message\":\"Unauthorized\"}"));
        SaluteSpeechTextToSpeech wrongKey = adapter(AUTH_KEY, "SALUTE_SPEECH_CORP", "", "");
        assertThatThrownBy(() -> wrongKey.synthesize("Строка")).isInstanceOf(TextToSpeechException.class)
                .hasMessageContaining("SALUTE_AUTH_KEY").hasMessageContaining("SALUTE_SCOPE")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(AUTH_KEY));
        assertThat(speechCalls).isEmpty();

        oauth.set(call -> Answer.json(200, "{\"error\":\"no token here\"}"));
        assertThatThrownBy(() -> wrongKey.synthesize("Строка")).hasMessageContaining("access_token");

        oauth.set(call -> Answer.json(503, "upstream down"));
        assertThatThrownBy(() -> wrongKey.synthesize("Строка")).hasMessageContaining("503");
        assertThat(speechCalls).isEmpty();
    }

    @Test
    void withoutAKeyOrATextNothingLeavesTheServer() {
        SaluteSpeechTextToSpeech noKey = adapter("", "", "", "");
        assertThat(noKey.configured()).isFalse();
        assertThatThrownBy(() -> noKey.synthesize("Строка")).hasMessageContaining("SALUTE_AUTH_KEY");

        SaluteSpeechTextToSpeech tts = adapter(AUTH_KEY, "", "", "");
        assertThatThrownBy(() -> tts.synthesize("   ")).isInstanceOf(TextToSpeechException.class);
        assertThatThrownBy(() -> tts.synthesize("ё".repeat(SaluteSpeechTextToSpeech.TEXT_LIMIT + 1)))
                .hasMessageContaining(String.valueOf(SaluteSpeechTextToSpeech.TEXT_LIMIT));
        assertThat(oauthCalls).isEmpty();
        assertThat(speechCalls).isEmpty();
    }

    @Test
    void anUnreachableEndpointIsAnExceptionNotAHang() {
        SaluteSpeechTextToSpeech tts = new SaluteSpeechTextToSpeech(HttpClient.newHttpClient(),
                new SaluteSpeechTextToSpeech.Settings(AUTH_KEY, "", "", "", "", "http://127.0.0.1:1/api/v2/oauth",
                        "http://127.0.0.1:1/rest/v1/text:synthesize", Duration.ofSeconds(2)));
        assertThatThrownBy(() -> tts.synthesize("Строка")).isInstanceOf(TextToSpeechException.class)
                .hasMessageContaining("unreachable")
                .satisfies(e -> assertThat(((TextToSpeechException) e).status()).isZero());
    }

    @Test
    void aMissingCaFileFailsAtConstructionInsteadOfRelaxingTls() {
        assertThatThrownBy(() -> new SaluteSpeechTextToSpeech(new SaluteSpeechTextToSpeech.Settings(
                AUTH_KEY, "", "", "", "/nonexistent/russian_trusted_root_ca.pem", "", "", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SALUTE_CA_CERT_PATH");
        // A readable PEM is accepted next to the JVM defaults (the same trust GigaChat uses).
        SaluteSpeechTextToSpeech withCa = new SaluteSpeechTextToSpeech(new SaluteSpeechTextToSpeech.Settings(
                AUTH_KEY, "", "", "", "src/test/resources/certs/test-root-ca.pem", "", "", null));
        assertThat(withCa.synthesisUri().toString())
                .isEqualTo("https://smartspeech.sber.ru/rest/v1/text:synthesize?format=wav16&voice=Nec_24000");
        List<String> defaults = new ArrayList<>();
        defaults.add(SaluteSpeechTextToSpeech.DEFAULT_OAUTH_URL);
        assertThat(defaults).containsExactly("https://ngw.devices.sberbank.ru:9443/api/v2/oauth");
    }
}
