package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.glasses.GlassesTranscriptFeed;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GlassesTranscriptControllerTest {
    private static final String TOKEN = "unit-relay-token-not-real";
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private static final String SESSION = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
    private final ObjectMapper mapper = new ObjectMapper();
    private final GlassesTranscriptFeed feed = new GlassesTranscriptFeed();
    private final TelegramSystemNotifier notifier = mock(TelegramSystemNotifier.class);
    private final GlassesTranscriptController controller = new GlassesTranscriptController(feed, notifier, TOKEN, mapper);

    private MockHttpServletRequest request(String json, String token) {
        var request = new MockHttpServletRequest("POST", "/api/internal/glasses/transcript");
        if (token != null) request.addHeader("X-Astor-Relay-Token", token);
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String payload(Map<String, ?> extra) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", ID);
        body.put("kind", "text");
        body.put("staff", "staff-1");
        body.put("venue", "AERIS");
        body.put("question", "Что по бизнес-ланчу?");
        body.put("answer", "Сегодня суп и паста.");
        body.put("at", Instant.now().toString());
        body.putAll(extra);
        return mapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private String code(Object body) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) body).get("error")).get("code");
    }

    @Test void anExchangeGoesToTheSystemChatAndTheShiftFeed() throws Exception {
        when(notifier.sendGlassesExchange(any(), any(), any(), any(), any(), any())).thenReturn(true);
        var result = controller.transcript(request(payload(Map.of()), TOKEN));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        var accepted = (GlassesTranscriptController.Accepted) result.getBody();
        assertThat(accepted.chat()).isTrue();
        assertThat(accepted.feed()).isTrue();
        verify(notifier).sendGlassesExchange(eq("staff-1"), eq(null), eq("Что по бизнес-ланчу?"), eq("Сегодня суп и паста."), eq(null), any());
        assertThat(feed.recent(10)).singleElement().satisfies(entry -> {
            assertThat(entry.question()).isEqualTo("Что по бизнес-ланчу?");
            assertThat(entry.answer()).isEqualTo("Сегодня суп и паста.");
            assertThat(entry.photo()).isFalse();
        });
    }

    @Test void aPhotoTravelsToTheChatButTheFeedOnlyRemembersThatThereWasOne() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 7, 7};
        var result = controller.transcript(request(payload(Map.of("kind", "image", "sessionId", SESSION,
                "scenarioCode", "BUSINESS_LUNCH_TWO", "stageCode", "PLACE_SETTINGS",
                "photoBase64", Base64.getEncoder().encodeToString(jpeg), "photoMimeType", "image/jpeg")), TOKEN));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(notifier).sendGlassesExchange(eq("staff-1"), eq("PLACE_SETTINGS"), any(), any(),
                argThat(bytes -> bytes != null && bytes.length == 4), any());
        var entry = feed.recent(1).get(0);
        assertThat(entry.photo()).isTrue();
        assertThat(entry.stageCode()).isEqualTo("PLACE_SETTINGS");
        assertThat(entry.sessionId()).isEqualTo(SESSION);
    }

    @Test void withoutTheSharedTokenNothingIsRepeatedAnywhere() throws Exception {
        assertThat(controller.transcript(request(payload(Map.of()), null)).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.transcript(request(payload(Map.of()), "wrong-token-value-here")).getStatusCode().value()).isEqualTo(401);

        var unconfigured = new GlassesTranscriptController(feed, notifier, "short", mapper);
        var result = unconfigured.transcript(request(payload(Map.of()), "short"));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(code(result.getBody())).isEqualTo("RELAY_UNAVAILABLE");

        verifyNoInteractions(notifier);
        assertThat(feed.recent(10)).isEmpty();
    }

    @Test void refusesMalformedOversizedAndStalePayloads() throws Exception {
        var rejected = new String[]{
                payload(Map.of("kind", "video")),
                payload(Map.of("answer", "   ")),
                payload(Map.of("requestId", "nope")),
                payload(Map.of("at", "2020-01-01T00:00:00Z")),
                payload(Map.of("at", "вчера")),
                payload(Map.of("taskId", "7")),
                payload(Map.of("photoBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2}), "photoMimeType", "image/jpeg")),
                payload(Map.of("kind", "image", "photoBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2}), "photoMimeType", "image/jpeg")),
                "{}"};
        for (String body : rejected) {
            var result = controller.transcript(request(body, TOKEN));
            assertThat(result.getStatusCode().value()).as(body).isBetween(400, 413);
        }
        verifyNoInteractions(notifier);
        assertThat(feed.recent(10)).isEmpty();
    }

    @Test void theFeedIsBoundedAndTheNewestComesFirst() {
        for (int i = 0; i < GlassesTranscriptFeed.LIMIT + 5; i++) {
            feed.add(new GlassesTranscriptFeed.Entry(java.util.UUID.randomUUID().toString(), Instant.now().toString(),
                    "text", "staff-1", "AERIS", null, null, "вопрос " + i, "ответ " + i, false));
        }
        var recent = feed.recent(GlassesTranscriptFeed.LIMIT);
        assertThat(recent).hasSize(GlassesTranscriptFeed.LIMIT);
        assertThat(recent.get(0).answer()).isEqualTo("ответ " + (GlassesTranscriptFeed.LIMIT + 4));

        feed.add(new GlassesTranscriptFeed.Entry(ID, Instant.now().minus(java.time.Duration.ofHours(25)).toString(),
                "text", "staff-1", "AERIS", null, null, "старое", "старое", false));
        assertThat(feed.recent(GlassesTranscriptFeed.LIMIT)).noneSatisfy(e -> assertThat(e.answer()).isEqualTo("старое"));
    }

    @Test void thePageEscapesWhatWasSaidAndServesNoFrames() {
        feed.add(new GlassesTranscriptFeed.Entry(ID, Instant.now().toString(), "image", "staff-1", "AERIS",
                SESSION, "PLACE_SETTINGS", "<b>Что</b> на столе?", "Видны приборы & бокалы", true));
        String html = GlassesFeedController.render(feed.recent(10));
        assertThat(html).doesNotContain("<script").doesNotContain("<b>Что</b>");
        assertThat(html).contains("&lt;b&gt;Что").contains("&amp; бокалы").contains("фото в чате").contains("PLACE_SETTINGS");
        assertThat(html).doesNotContain("photoBase64").doesNotContain("<img");
        assertThat(GlassesFeedController.render(java.util.List.of())).contains("Пока ничего");
    }
}
