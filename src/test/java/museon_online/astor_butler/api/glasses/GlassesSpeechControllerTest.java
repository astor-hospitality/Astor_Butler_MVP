package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesSpeechControllerTest {
    private static final String TOKEN = "unit-test-only-not-a-real-credential";
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final GlassesVoice voice = mock(GlassesVoice.class);
    private final GlassesSpeech speech = mock(GlassesSpeech.class);

    private GlassesAccess access() {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
            return new GlassesAccess(hash, "test-venue", "test-staff", Instant.now().plusSeconds(60).toString());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private MockHttpServletRequest request(String path, String json, String bearer) {
        var request = new MockHttpServletRequest("POST", path);
        if (bearer != null) request.addHeader("Authorization", "Bearer " + bearer);
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String code(Object body) {
        return ((GlassesController.ErrorResponse) body).error().get("code");
    }

    private static String m4a() {
        byte[] media = new byte[32];
        ByteBuffer.wrap(media).putInt(32);
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, media, 4, 4);
        return Base64.getEncoder().encodeToString(media);
    }

    private GlassesSpeechController controller(GlassesAssistService service) {
        return new GlassesSpeechController(access(), service, mapper);
    }

    @Test void readsALineInAstorsOwnMaleVoice() throws Exception {
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, 7};
        when(speech.configured()).thenReturn(true);
        when(speech.synthesize("Стол пять просит счёт.")).thenReturn(mp3);
        when(speech.voiceName()).thenReturn("filipp");
        when(speech.voiceGender()).thenReturn("male");
        when(speech.mimeType()).thenReturn("audio/mpeg");
        try (var service = new GlassesAssistService(gateway, voice, speech, true, 1000)) {
            var result = controller(service).speech(request("/api/glasses/speech",
                    mapper.writeValueAsString(Map.of("requestId", ID, "text", "Стол пять просит счёт.")), TOKEN));
            assertThat(result.getStatusCode().value()).isEqualTo(200);
            var body = (GlassesSpeechController.Speech) result.getBody();
            assertThat(body.requestId()).isEqualTo(ID);
            assertThat(Base64.getDecoder().decode(body.audioBase64())).isEqualTo(mp3);
            assertThat(body.audioMimeType()).isEqualTo("audio/mpeg");
            assertThat(body.audioVoiceGender()).isEqualTo("male");
            assertThat(body.voice()).isEqualTo("filipp");
        }
        verifyNoInteractions(gateway);
    }

    @Test void saysSoWhenTheServerVoiceIsOffOrFails() throws Exception {
        try (var off = new GlassesAssistService(gateway, voice, GlassesSpeech.disabled(), true, 1000)) {
            var result = controller(off).speech(request("/api/glasses/speech",
                    mapper.writeValueAsString(Map.of("requestId", ID, "text", "Строка")), TOKEN));
            assertThat(result.getStatusCode().value()).isEqualTo(503);
            assertThat(code(result.getBody())).isEqualTo("SPEECH_UNAVAILABLE");
        }
        when(speech.configured()).thenReturn(true);
        when(speech.synthesize(any())).thenReturn(null);
        try (var failing = new GlassesAssistService(gateway, voice, speech, true, 1000)) {
            assertThat(controller(failing).speech(request("/api/glasses/speech",
                    mapper.writeValueAsString(Map.of("requestId", ID, "text", "Строка")), TOKEN))
                    .getStatusCode().value()).isEqualTo(503);
        }
    }

    @Test void turnsOneRecordingIntoTextAndNothingElse() throws Exception {
        when(voice.transcribe(any())).thenReturn("Передайте, что стол готов");
        try (var service = new GlassesAssistService(gateway, voice, speech, true, 1000)) {
            var result = controller(service).transcribe(request("/api/glasses/transcribe",
                    mapper.writeValueAsString(Map.of("requestId", ID, "audioBase64", m4a(), "audioMimeType", "audio/mp4")), TOKEN));
            assertThat(result.getStatusCode().value()).isEqualTo(200);
            var body = (GlassesSpeechController.Transcript) result.getBody();
            assertThat(body.requestId()).isEqualTo(ID);
            assertThat(body.text()).isEqualTo("Передайте, что стол готов");
        }
        // No model call, no synthesis: the words are the staff member's, and sending them is their decision.
        verifyNoInteractions(gateway);
        verify(speech, never()).synthesize(any());
    }

    @Test void refusesAnythingButOneBoundedAacRecordingAndRequiresTheBearer() throws Exception {
        try (var service = new GlassesAssistService(gateway, voice, speech, true, 1000)) {
            var controller = controller(service);
            var unauthorized = controller.transcribe(request("/api/glasses/transcribe", "{}", null));
            assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
            assertThat(unauthorized.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");

            for (Map<String, ?> body : java.util.List.of(
                    Map.of("requestId", "nope", "audioBase64", m4a(), "audioMimeType", "audio/mp4"),
                    Map.of("requestId", ID, "audioBase64", m4a(), "audioMimeType", "audio/mpeg"),
                    Map.of("requestId", ID, "audioBase64", Base64.getEncoder().encodeToString("not media".getBytes(StandardCharsets.UTF_8)), "audioMimeType", "audio/mp4"),
                    Map.of("requestId", ID, "audioMimeType", "audio/mp4"))) {
                var result = controller.transcribe(request("/api/glasses/transcribe", mapper.writeValueAsString(body), TOKEN));
                assertThat(result.getStatusCode().value()).as(body.toString()).isEqualTo(400);
                assertThat(code(result.getBody())).isEqualTo("MALFORMED_REQUEST");
            }
            verifyNoInteractions(voice);
        }
    }
}
