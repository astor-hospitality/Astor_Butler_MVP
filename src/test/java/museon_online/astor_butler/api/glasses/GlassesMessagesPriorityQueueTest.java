package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.ModelGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The outbox with ASTOR_GLASSES_MESSAGE_PRIORITY_ENABLED (issue #186): alerts at once, the rest in a pause,
 * the overflow on the screen behind one line, repeats dropped — and, with the flag off, the JSON of before.
 */
class GlassesMessagesPriorityQueueTest {
    private static final String TOKEN = "unit-test-only-not-a-real-credential";
    private static final String PASSWORD = "unit-report-password-not-real";
    private final ObjectMapper mapper = new ObjectMapper();
    private final GlassesAccess access = access();
    private final GlassesAssistService service = new GlassesAssistService(mock(ModelGateway.class), true, 1000);
    private final GlassesReportAuth reportAuth = new GlassesReportAuth(access, PASSWORD);
    private final TestClock clock = new TestClock(Instant.parse("2026-10-12T12:00:00Z"));

    @AfterEach void close() { service.close(); }

    /** A clock the test moves by hand, so «within a minute» and «two minutes later» are exact. */
    private static final class TestClock extends Clock {
        private Instant now;
        TestClock(Instant start) { this.now = start; }
        void tick(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Each controller gets its own report auth: its 30 requests a minute would otherwise be shared by the test. */
    private GlassesMessagesController controller(GlassesMessagePriority priority) {
        return new GlassesMessagesController(access, service, new GlassesReportAuth(access, PASSWORD), mapper, priority, clock);
    }

    private GlassesMessagesController enabled(int perMinute) {
        return controller(GlassesMessagePriority.enabled(perMinute));
    }

    private GlassesAccess access() {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
            return new GlassesAccess(hash, "test-venue", "test-staff", Instant.now().plusSeconds(600).toString());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private MockHttpServletRequest post(String json) {
        var request = new MockHttpServletRequest("POST", "/api/glasses/messages");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(("astor:" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletRequest get() {
        var request = new MockHttpServletRequest("GET", "/api/glasses/messages");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private String json(Object... pairs) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) body.put((String) pairs[i], pairs[i + 1]);
        return mapper.writeValueAsString(body);
    }

    /** Queues {"text": …} and expects it accepted. */
    private GlassesMessagesController.Queued queue(GlassesMessagesController controller, String text) throws Exception {
        return send(controller, json("text", text));
    }

    /** Queues a raw JSON body and expects it accepted. */
    private GlassesMessagesController.Queued send(GlassesMessagesController controller, String body) throws Exception {
        ResponseEntity<?> response = controller.queue(post(body));
        assertThat(response.getStatusCode().value()).as(body).isEqualTo(200);
        return (GlassesMessagesController.Queued) response.getBody();
    }

    private List<GlassesMessagesController.Message> pending(GlassesMessagesController controller) {
        return controller.pending(new GlassesAccess.Scope("test-venue", "test-staff"));
    }

    private String code(ResponseEntity<?> response) {
        return ((GlassesController.ErrorResponse) response.getBody()).error().get("code");
    }

    /* ---------- the flag off: nothing changes ---------- */

    @Test void withTheFlagOffTheJsonIsByteForByteWhatItWas() throws Exception {
        var controller = controller(GlassesMessagePriority.disabled());
        var queued = controller.queue(post(json("text", "Стол пять просит счёт.")));
        String id = ((GlassesMessagesController.Queued) queued.getBody()).id();
        assertThat(mapper.writeValueAsString(queued.getBody())).isEqualTo("{\"id\":\"" + id + "\",\"pending\":1}");
        assertThat(mapper.writeValueAsString(controller.messages(get()).getBody())).isEqualTo(
                "{\"messages\":[{\"id\":\"" + id + "\",\"text\":\"Стол пять просит счёт.\",\"createdAt\":\"2026-10-12T12:00:00Z\"}]}");
        // The same text again is a second message, as before: no repeat check without the flag.
        assertThat(((GlassesMessagesController.Queued) controller.queue(post(json("text", "Стол пять просит счёт."))).getBody()).pending()).isEqualTo(2);
        // The new request fields are still unknown fields.
        for (String body : new String[]{json("text", "Аллергия", "priority", "ALERT"), json("text", "Аллергия", "source", "ESCALATION")}) {
            var refused = controller.queue(post(body));
            assertThat(refused.getStatusCode().value()).as(body).isEqualTo(400);
            assertThat(code(refused)).isEqualTo("MALFORMED_REQUEST");
        }
        // The four-argument constructor is the flag-off queue, so nothing wired without the bean changes either.
        var legacy = new GlassesMessagesController(access, service, reportAuth, mapper);
        var receipt = legacy.queue(post(json("text", "Срочно: аллергия у стола 5")));
        assertThat(mapper.writeValueAsString(receipt.getBody())).doesNotContain("priority");
        assertThat(mapper.writeValueAsString(legacy.messages(get()).getBody())).doesNotContain("priority", "quiet", "speak");
    }

    /* ---------- the flag on ---------- */

    @Test void alertsAreSpokenAtOnceAndOrdinaryMessagesInAPause() throws Exception {
        var controller = enabled(6);
        var alert = queue(controller, "У стола 5 гость с аллергией на орехи");
        assertThat(alert.priority()).isEqualTo("ALERT");
        assertThat(alert.quiet()).isFalse();
        assertThat(alert.speak()).isTrue();
        assertThat(alert.duplicate()).isNull();

        var normal = queue(controller, "Принеси воду на третий стол");
        assertThat(normal.priority()).isEqualTo("NORMAL");
        assertThat(normal.quiet()).isTrue();
        assertThat(normal.speak()).isTrue();

        var escalation = send(controller, json("text", "Бронь 18:00 передана менеджеру", "source", "ESCALATION"));
        var killSwitch = send(controller, json("text", "Автоподтверждение выключено", "source", "kill-switch"));
        var highTask = send(controller, json("text", "Убрать стол 4", "source", "STAFF_TASK", "priority", "HIGH"));
        var plainTask = send(controller, json("text", "Протереть стол 4", "source", "STAFF_TASK", "priority", "NORMAL"));
        var asked = send(controller, json("text", "Позвать к столу 9", "priority", "alert"));
        assertThat(List.of(escalation.priority(), killSwitch.priority(), highTask.priority(), asked.priority()))
                .containsOnly("ALERT");
        assertThat(plainTask.priority()).isEqualTo("NORMAL");

        var messages = pending(controller);
        assertThat(messages).hasSize(7);
        assertThat(messages).extracting(GlassesMessagesController.Message::source)
                .containsExactly("RESTAURANT", "RESTAURANT", "ESCALATION", "KILL_SWITCH", "STAFF_TASK", "STAFF_TASK", "RESTAURANT");
        assertThat(messages).filteredOn(GlassesMessagesController.Message::alert).hasSize(5)
                .allSatisfy(m -> { assertThat(m.quiet()).isFalse(); assertThat(m.speak()).isTrue(); });
        assertThat(messages).filteredOn(m -> !m.alert()).hasSize(2)
                .allSatisfy(m -> { assertThat(m.quiet()).isTrue(); assertThat(m.speak()).isTrue(); });
        assertThat(messages).extracting(GlassesMessagesController.Message::coalesced).containsOnlyNulls();
    }

    @Test void beyondTheRateOrdinaryMessagesStayOnTheScreenBehindOneSpokenLine() throws Exception {
        var controller = enabled(3);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            clock.tick(Duration.ofSeconds(5));
            var receipt = queue(controller, "Сообщение номер " + i);
            ids.add(receipt.id());
            assertThat(receipt.priority()).isEqualTo("NORMAL");
            assertThat(receipt.quiet()).isTrue();
            assertThat(receipt.speak()).as("message " + i).isEqualTo(i <= 3);
        }
        var messages = pending(controller);
        assertThat(messages).hasSize(6);
        // Held messages keep their place in the list; the line stands right after the first one it speaks for.
        var summary = messages.get(4);
        assertThat(messages).extracting(GlassesMessagesController.Message::id)
                .containsExactly(ids.get(0), ids.get(1), ids.get(2), ids.get(3), summary.id(), ids.get(4));
        assertThat(messages).filteredOn(m -> !m.summary()).extracting(GlassesMessagesController.Message::speak)
                .containsExactly(true, true, true, false, false);
        assertThat(messages).filteredOn(m -> !m.summary()).extracting(GlassesMessagesController.Message::coalesced).containsOnlyNulls();
        assertThat(summary.text()).isEqualTo("Ещё 2 сообщения — на экране");
        assertThat(summary.coalesced()).isEqualTo(2);
        assertThat(summary.priority()).isEqualTo("NORMAL");
        assertThat(summary.quiet()).isTrue();
        assertThat(summary.speak()).isTrue();
        assertThat(summary.source()).isNull();
        assertThat(summary.createdAt()).isEqualTo(messages.get(3).createdAt());

        // One more in the same minute grows the same line, in the same place; its id does not change.
        clock.tick(Duration.ofSeconds(5));
        queue(controller, "Сообщение номер 6");
        var grown = pending(controller);
        assertThat(grown).hasSize(7);
        assertThat(grown.get(4).id()).isEqualTo(summary.id());
        assertThat(grown.get(4).text()).isEqualTo("Ещё 3 сообщения — на экране");
        assertThat(grown.get(4).coalesced()).isEqualTo(3);
        assertThat(grown.get(6).text()).isEqualTo("Сообщение номер 6");
        assertThat(grown.get(6).speak()).isFalse();

        // A minute later the rate is free again, and a later overflow gets a line of its own.
        clock.tick(Duration.ofSeconds(61));
        assertThat(queue(controller, "Сообщение номер 7").speak()).isTrue();
        assertThat(queue(controller, "Сообщение номер 8").speak()).isTrue();
        assertThat(queue(controller, "Сообщение номер 9").speak()).isTrue();
        assertThat(queue(controller, "Сообщение номер 10").speak()).isFalse();
        var later = pending(controller);
        assertThat(later).filteredOn(GlassesMessagesController.Message::summary).hasSize(2);
        assertThat(later.get(later.size() - 1).text()).isEqualTo("Ещё 1 сообщение — на экране");
        assertThat(later.get(later.size() - 1).id()).isNotEqualTo(summary.id());
    }

    @Test void alertsAreNeverCountedOrCoalescedAndMakeRoomInAFullOutbox() throws Exception {
        var controller = enabled(2);
        queue(controller, "Первое обычное");
        queue(controller, "Второе обычное");
        assertThat(queue(controller, "Третье обычное").speak()).isFalse();
        for (int i = 0; i < 3; i++) {
            var alert = queue(controller, "Срочно: алерт " + i);
            assertThat(alert.speak()).isTrue();
            assertThat(alert.quiet()).isFalse();
        }
        var messages = pending(controller);
        assertThat(messages).filteredOn(GlassesMessagesController.Message::summary).singleElement()
                .extracting(GlassesMessagesController.Message::coalesced).isEqualTo(1);
        assertThat(messages).filteredOn(GlassesMessagesController.Message::alert).hasSize(3);
        // Still one ordinary message over the rate, so the next ordinary one is held as well.
        assertThat(queue(controller, "Четвёртое обычное").speak()).isFalse();

        // Fill the outbox with ordinary messages; an alert still goes in, at the cost of the oldest ordinary one.
        var full = enabled(100);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < GlassesMessagesController.PENDING_LIMIT; i++) ids.add(queue(full, "Обычное " + i).id());
        var refused = full.queue(post(json("text", "Ещё одно обычное")));
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(code(refused)).isEqualTo("OUTBOX_FULL");
        var alert = queue(full, "Аллергия у стола 2");
        var afterAlert = pending(full);
        assertThat(afterAlert).hasSize(GlassesMessagesController.PENDING_LIMIT);
        assertThat(afterAlert.get(0).id()).isEqualTo(ids.get(1));
        assertThat(afterAlert.get(afterAlert.size() - 1).id()).isEqualTo(alert.id());
        assertThat(afterAlert).extracting(GlassesMessagesController.Message::id).doesNotContain(ids.get(0));

        // Only alerts left: nothing gives way, and the outbox is honestly full.
        var alerts = enabled(100);
        for (int i = 0; i < GlassesMessagesController.PENDING_LIMIT; i++) queue(alerts, "Срочно " + i);
        var noRoom = alerts.queue(post(json("text", "Срочно ещё")));
        assertThat(noRoom.getStatusCode().value()).isEqualTo(429);
        assertThat(code(noRoom)).isEqualTo("OUTBOX_FULL");
    }

    @Test void aRepeatedTextWithinTwoMinutesIsOneMessage() throws Exception {
        var controller = enabled(6);
        var first = queue(controller, "Стол 5 просит счёт.");
        var again = queue(controller, "  стол 5  просит счет ");
        assertThat(again.duplicate()).isTrue();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.pending()).isEqualTo(1);
        assertThat(again.priority()).isEqualTo("NORMAL");
        assertThat(again.quiet()).isTrue();
        assertThat(pending(controller)).hasSize(1);

        // An alert repeated is dropped the same way: the earpiece hears it once.
        var alert = queue(controller, "Аллергия на орехи, стол 5");
        var alertAgain = queue(controller, "Аллергия на орехи, стол 5");
        assertThat(alertAgain.duplicate()).isTrue();
        assertThat(alertAgain.id()).isEqualTo(alert.id());
        assertThat(alertAgain.quiet()).isFalse();
        assertThat(pending(controller)).hasSize(2);

        // Another text is another message; the same text after the window is new again.
        assertThat(queue(controller, "Стол 6 просит счёт.").duplicate()).isNull();
        clock.tick(Duration.ofMinutes(2).plusSeconds(1));
        var later = queue(controller, "Стол 5 просит счёт.");
        assertThat(later.duplicate()).isNull();
        assertThat(later.id()).isNotEqualTo(first.id());
        assertThat(pending(controller)).hasSize(4);
    }

    @Test void theSummaryLineIsNotAMessageForTheRepeatCheck() throws Exception {
        var controller = enabled(1);
        queue(controller, "Первое");
        queue(controller, "Второе");
        var ownWords = queue(controller, "Ещё 1 сообщение — на экране");
        assertThat(ownWords.duplicate()).isNull();
        assertThat(pending(controller)).filteredOn(GlassesMessagesController.Message::summary).singleElement()
                .extracting(GlassesMessagesController.Message::coalesced).isEqualTo(2);
    }

    @Test void unknownSourcesPrioritiesAndFieldsAreMalformed() throws Exception {
        var controller = enabled(6);
        for (String body : new String[]{
                json("text", "Текст", "source", "kitchen"),
                json("text", "Текст", "priority", "LOW"),
                json("text", "Текст", "priority", 1),
                json("text", "Текст", "staffId", "7"),
                "{\"text\":\"Текст\",\"source\":[\"RESTAURANT\"]}"}) {
            var refused = controller.queue(post(body));
            assertThat(refused.getStatusCode().value()).as(body).isEqualTo(400);
            assertThat(code(refused)).isEqualTo("MALFORMED_REQUEST");
        }
        // A null in place of an optional field is the same as leaving it out.
        var receipt = send(controller, "{\"text\":\"Текст\",\"source\":null,\"priority\":null}");
        assertThat(receipt.priority()).isEqualTo("NORMAL");
        assertThat(pending(controller)).singleElement().extracting(GlassesMessagesController.Message::source).isEqualTo("RESTAURANT");
    }

    @Test void theFieldContractForThePhone() throws Exception {
        var controller = enabled(1);
        queue(controller, "Аллергия на орехи у стола 5");
        queue(controller, "Принеси воду на третий стол");
        queue(controller, "Протри стол 4");
        var receipt = controller.queue(post(json("text", "Протри стол 4")));
        JsonNode queued = mapper.readTree(mapper.writeValueAsString(receipt.getBody()));
        assertThat(names(queued)).containsExactly("id", "pending", "priority", "quiet", "speak", "duplicate");

        JsonNode body = mapper.readTree(mapper.writeValueAsString(controller.messages(get()).getBody()));
        assertThat(names(body)).containsExactly("messages");
        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(4);

        JsonNode alert = messages.get(0);
        assertThat(names(alert)).containsExactly("id", "text", "createdAt", "priority", "quiet", "speak", "source");
        assertThat(alert.get("priority").asText()).isEqualTo("ALERT");
        assertThat(alert.get("quiet").asBoolean()).isFalse();
        assertThat(alert.get("speak").asBoolean()).isTrue();
        assertThat(alert.get("source").asText()).isEqualTo("RESTAURANT");

        JsonNode spoken = messages.get(1);
        assertThat(names(spoken)).containsExactly("id", "text", "createdAt", "priority", "quiet", "speak", "source");
        assertThat(spoken.get("priority").asText()).isEqualTo("NORMAL");
        assertThat(spoken.get("quiet").asBoolean()).isTrue();
        assertThat(spoken.get("speak").asBoolean()).isTrue();

        JsonNode held = messages.get(2);
        assertThat(held.get("text").asText()).isEqualTo("Протри стол 4");
        assertThat(held.get("quiet").asBoolean()).isTrue();
        assertThat(held.get("speak").asBoolean()).isFalse();

        JsonNode summary = messages.get(3);
        assertThat(names(summary)).containsExactly("id", "text", "createdAt", "priority", "quiet", "speak", "coalesced");
        assertThat(summary.get("text").asText()).isEqualTo("Ещё 1 сообщение — на экране");
        assertThat(summary.get("coalesced").asInt()).isEqualTo(1);
        assertThat(summary.get("speak").asBoolean()).isTrue();
        assertThat(summary.get("quiet").asBoolean()).isTrue();
        for (JsonNode message : messages) {
            assertThat(java.util.UUID.fromString(message.get("id").asText())).isNotNull();
            assertThat(Instant.parse(message.get("createdAt").asText())).isNotNull();
        }
    }

    @Test void theQueueStillBelongsToOneScopeAndExpiresWithTheFlagOn() throws Exception {
        var controller = enabled(6);
        queue(controller, "Для этой смены");
        assertThat(controller.pending(new GlassesAccess.Scope("other-venue", "test-staff"))).isEmpty();
        assertThat(controller.pending(new GlassesAccess.Scope("test-venue", "test-staff"))).hasSize(1);
        clock.tick(GlassesMessagesController.TTL.plusSeconds(1));
        assertThat(((GlassesMessagesController.Pending) controller.messages(get()).getBody()).messages()).isEmpty();
        // Expired, so the same text is a new message, not a repeat.
        assertThat(queue(controller, "Для этой смены").duplicate()).isNull();
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
