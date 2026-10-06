package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlassesControllerTest {
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private static final String TOKEN = "unit-test-only-not-a-real-credential";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final GlassesAssistService service = new GlassesAssistService(gateway, true, 1000);
    private final GlassesController controller = new GlassesController(access(Instant.now().plusSeconds(60).toString()), service, mapper);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @AfterEach void close() { service.close(); }

    private GlassesAccess access(String expiry) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
            return new GlassesAccess(hash, "test-venue", "test-staff", expiry);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private MockHttpServletRequest request(String content) {
        var request = new MockHttpServletRequest("POST", "/api/glasses/assist");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.setContentType("application/json");
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String json(Map<String, ?> fields) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("requestId", ID);
        body.putAll(fields);
        return mapper.writeValueAsString(body);
    }

    private void rejects(String content, int status, String code) {
        var result = controller.assist(request(content));
        assertThat(result.getStatusCode().value()).isEqualTo(status);
        var error = (GlassesController.ErrorResponse) result.getBody();
        assertThat(error.error()).containsEntry("code", code);
        verifyNoInteractions(gateway);
    }

    @Test void authorizesBeforeReadingBody() throws Exception {
        mvc.perform(post("/api/glasses/assist").contentType("application/json").content("invalid"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/glasses/capabilities").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(gateway);
    }

    @Test void failsClosedWhenCredentialIsUnconfigured() {
        var c = new GlassesController(new GlassesAccess("", "", "", ""), service, mapper);
        assertThat(c.assist(request("{} ")).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(gateway);
    }

    @Test void expiresAndRequiresServerSideStaffScope() {
        for (var a : new GlassesAccess[]{access("2000-01-01T00:00:00Z"), access("invalid"),
                new GlassesAccess(tokenHash(), "", "test-staff", Instant.now().plusSeconds(60).toString())}) {
            var c = new GlassesController(a, service, mapper);
            assertThat(c.assist(request("{} ")).getStatusCode().value()).isEqualTo(403);
        }
        verifyNoInteractions(gateway);
    }

    private String tokenHash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void textUsesOnlyGatewayAndEchoesCorrelation() throws Exception {
        when(gateway.generateText(any())).thenReturn(ModelTextResponse.text("Уточните регламент у менеджера.", "test", "test", Duration.ZERO));
        mvc.perform(post("/api/glasses/assist").header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content(json(Map.of("text", "Как сервировать стол?"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(ID))
                .andExpect(jsonPath("$.text").value("Уточните регламент у менеджера."))
                .andExpect(jsonPath("$.capabilities.voice").value(false))
                .andExpect(jsonPath("$.capabilities.vision").value(false));
        verify(gateway).generateText(argThat(r -> r.scenario().equals("GLASSES_INFORMATIONAL")
                && r.state().equals("READ_ONLY") && r.metadata().isEmpty()));
        verifyNoMoreInteractions(gateway);
    }

    @Test void capabilitiesDoNotTreatConfigurationAsReadiness() throws Exception {
        mvc.perform(get("/api/glasses/capabilities").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value(false))
                .andExpect(jsonPath("$.voice").value(false)).andExpect(jsonPath("$.vision").value(false))
                .andExpect(jsonPath("$.maxAudioSeconds").value(30));
        verifyNoInteractions(gateway);
    }

    @Test void identicalNetworkRetryReusesReplyButChangedInputIsConflict() throws Exception {
        when(gateway.generateText(any())).thenReturn(ModelTextResponse.text("Ответ для повтора.", "test", "test", Duration.ZERO));
        String body = json(Map.of("text", "повтори"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/glasses/assist").header("Authorization", "Bearer " + TOKEN)
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Ответ для повтора."));
        }
        mvc.perform(post("/api/glasses/assist").header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content(json(Map.of("text", "другой вопрос"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.requestId").value(ID))
                .andExpect(jsonPath("$.error.code").value("REQUEST_ID_CONFLICT"));
        mvc.perform(post("/api/glasses/assist").header("Authorization", "Bearer wrong")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        verify(gateway, times(1)).generateText(any());
    }

    @ParameterizedTest @ValueSource(strings = {"", "   "})
    void blankProviderResponseIsUnavailable(String response) throws Exception {
        when(gateway.generateText(any())).thenReturn(ModelTextResponse.text(response, "test", "test", Duration.ZERO));
        var result = controller.assist(request(json(Map.of("text", "вопрос"))));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(service.capabilities().text()).isFalse();
    }

    @Test void fallbackAndProviderExceptionAreUnavailableWithoutLeakingDiagnostics() throws Exception {
        when(gateway.generateText(any())).thenThrow(new IllegalStateException("secret diagnostic"));
        var result = controller.assist(request(json(Map.of("text", "вопрос"))));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().toString()).doesNotContain("secret diagnostic");
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "null", "not-json",
            "{\"requestId\":\"x\",\"text\":\"question\"}",
            "{\"requestId\":\"ff5a8c58-bb60-43f4-b542-1e26c8b96581\",\"text\":17}",
            "{\"requestId\":\"ff5a8c58-bb60-43f4-b542-1e26c8b96581\",\"text\":\"a\",\"text\":\"b\"}",
            "{\"requestId\":\"ff5a8c58-bb60-43f4-b542-1e26c8b96581\",\"text\":\"a\"} {}"})
    void rejectsMalformedPayload(String body) { rejects(body, 400, "MALFORMED_REQUEST"); }

    @Test void rejectsClientIdentityUrlsAndMediaConflicts() throws Exception {
        for (Map<String, ?> fields : java.util.List.of(Map.of("text", "a", "tenant", "other"),
                Map.of("text", "a", "imageUrl", "https://example.invalid/a.jpg"),
                Map.of("audioBase64", "x", "imageBase64", "x"), Map.of("text", "a", "audioMimeType", "audio/mp4"),
                Map.of("audioBase64", "%%%", "audioMimeType", "audio/mp4"),
                Map.of("audioBase64", "eA==", "audioMimeType", "audio/mp4"),
                Map.of("audioBase64", "eA==", "audioMimeType", "audio/ogg"),
                Map.of("imageBase64", "eA==", "imageMimeType", "image/jpeg"))) {
            rejects(json(fields), 400, "MALFORMED_REQUEST");
        }
    }

    @Test void enforcesTextAndDecodedMediaLimits() throws Exception {
        rejects(json(Map.of("text", "я".repeat(4001))), 413, "PAYLOAD_TOO_LARGE");
        rejects(json(Map.of("audioMimeType", "audio/mp4", "audioBase64",
                Base64.getEncoder().encodeToString(new byte[2097153]))), 413, "PAYLOAD_TOO_LARGE");
    }

    @Test void enforcesActualBodyLimitWithoutContentLength() {
        var request = request(" ".repeat(5242881));
        request = new MockHttpServletRequestWrapperForUnknownLength(request);
        assertThat(controller.assist(request).getStatusCode().value()).isEqualTo(413);
        verifyNoInteractions(gateway);
    }

    private static class MockHttpServletRequestWrapperForUnknownLength extends MockHttpServletRequest {
        MockHttpServletRequestWrapperForUnknownLength(MockHttpServletRequest source) {
            setContent(source.getContentAsByteArray());
            setContentType("application/json");
            addHeader("Authorization", source.getHeader("Authorization"));
        }
        @Override public long getContentLengthLong() { return -1; }
    }

    @Test void recognizedAudioReturnsHonestUnavailableWithCorrelationAndNoTempFile() throws Exception {
        byte[] mp4 = ByteBuffer.allocate(20).putInt(20).put("ftypM4A ".getBytes(StandardCharsets.US_ASCII)).putLong(0).array();
        var result = controller.assist(request(json(Map.of("text", "", "audioBase64",
                Base64.getEncoder().encodeToString(mp4), "audioMimeType", "audio/mp4"))));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat((GlassesController.ErrorResponse) result.getBody()).satisfies(e -> {
            assertThat(e.requestId()).isEqualTo(ID);
            assertThat(e.error()).containsEntry("code", "VOICE_UNAVAILABLE");
        });
        verifyNoInteractions(gateway);
    }

    @Test void jpegDimensionsAreCheckedButVisionIsNeverFaked() throws Exception {
        rejects(json(Map.of("imageBase64", jpeg(1281), "imageMimeType", "image/jpeg")), 413, "PAYLOAD_TOO_LARGE");
        var result = controller.assist(request(json(Map.of("imageBase64", jpeg(32), "imageMimeType", "image/jpeg"))));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        verify(gateway).analyzeImage(any());
        verify(gateway, never()).generateText(any());
    }

    private String jpeg(int width) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, 2, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private Map<String, Object> photoContext() {
        return Map.of("sessionId", "80d26cf1-5139-4121-a4ca-dfb14aac225c", "scenarioCode", "BUSINESS_LUNCH_TWO",
                "stageCode", "PLACE_SETTINGS", "revision", 2);
    }

    @Test void photoContextIsImageOnlyAndCannotAssertIdentityTasksOrCompletion() throws Exception {
        rejects(json(Map.of("text", "a", "photoContext", photoContext())), 400, "MALFORMED_REQUEST");
        for (var entry : Map.<String, Object>of("sessionId", "invalid", "scenarioCode", "LIVE_TASK",
                "stageCode", "unknown", "revision", 0, "taskId", "invented", "archived", true).entrySet()) {
            var context = new java.util.HashMap<>(photoContext());
            context.put(entry.getKey(), entry.getValue());
            rejects(json(Map.of("imageBase64", jpeg(32), "imageMimeType", "image/jpeg", "photoContext", context)),
                    400, "MALFORMED_REQUEST");
        }
    }

    @Test void photoReceiptFollowsSuccessfulArchiveAndBoundContext() throws Exception {
        var storage = mock(GlassesS3Storage.class);
        when(storage.context(any())).thenReturn("");
        when(storage.enabled()).thenReturn(true);
        when(gateway.analyzeImage(any())).thenReturn(museon_online.astor_butler.model.ModelVisionResponse
                .vision("Проверьте два комплекта приборов.", "test", "test", Duration.ZERO));
        try (var photos = new GlassesAssistService(gateway, mock(GlassesVoice.class), storage, true, 1000)) {
            var c = new GlassesController(access(Instant.now().plusSeconds(60).toString()), photos, mapper);
            var result = c.assist(request(json(Map.of("imageBase64", jpeg(32), "imageMimeType", "image/jpeg",
                    "photoContext", photoContext()))));
            assertThat(result.getStatusCode().value()).isEqualTo(200);
            var receipt = ((GlassesController.AssistResponse) result.getBody()).photoReceipt();
            assertThat(receipt.archived()).isTrue();
            assertThat(receipt.requestId()).isEqualTo(ID);
            assertThat(receipt.context().stageCode()).isEqualTo("PLACE_SETTINGS");
            verify(storage).archive(any(), eq(ID), eq("image"), any(), any(), eq(receipt.context()));
        }
    }

    @Test void rateLimitsTheSinglePilotCredential() throws Exception {
        for (int i = 0; i < 10; i++) rejects(json(Map.of("text", "")), 400, "MALFORMED_REQUEST");
        rejects(json(Map.of("text", "")), 429, "RATE_LIMITED");
    }
}
