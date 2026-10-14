package museon_online.astor_butler.service.message;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncomingMessageTest {

    @Test
    void telegramPayloadWithNullValueKeepsTheMessageAndDropsTheNull() {
        // A reply to a user without a username, or a voice note without a mime type, carries null values.
        Map<String, Object> payload = new HashMap<>();
        payload.put("replyToMessageId", 7);
        payload.put("replyToUsername", null);

        IncomingMessage message = IncomingMessage.telegram(1L, 2L, 3, 4, "привет", null, null, null, null, null,
                false, "c", payload);

        assertThat(message.text()).isEqualTo("привет");
        assertThat(message.payload()).containsEntry("replyToMessageId", 7).doesNotContainKey("replyToUsername");
    }

    @Test
    void maxPayloadAndTextRewriteToleratePayloadNulls() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("maxMessageId", null);

        IncomingMessage message = IncomingMessage.max(1L, 2L, "hi", null, null, null, null, null, false, "c", payload);

        assertThat(message.payload()).isEmpty();
        assertThat(message.withTextAndPayload("x", payload).payload()).isEmpty();
    }
}
