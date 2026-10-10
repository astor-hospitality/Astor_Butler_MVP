package museon_online.astor_butler.domain.web;

import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebQuickReplyResolverTest {

    private final WebQuickReplyResolver resolver = new WebQuickReplyResolver("Бронь стола, Меню кухни,,Главное меню");

    private OutgoingMessage outgoing(String state, boolean requestContact, boolean removeKeyboard, Map<String, Object> metadata) {
        IncomingMessage incoming = new IncomingMessage(MessageChannel.WEB, "web:anon:s", 1L, 1L, null, null, "hi",
                null, null, null, null, null, false, "c", Instant.now(), Map.of());
        return OutgoingMessage.of(incoming, "text", state, false, requestContact, removeKeyboard, false, null, List.of())
                .withMetadata(metadata);
    }

    @Test
    void contactRequestWinsOverEverything() {
        List<WebQuickReply> replies = resolver.resolve(outgoing("READY_FOR_DIALOG", true, false,
                Map.of("replyKeyboardRows", List.of(List.of("Сегодня")))));
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).kind()).isEqualTo("contact");
        assertThat(replies.get(0).text()).isEqualTo(WebQuickReplyResolver.CONTACT_LABEL);
    }

    @Test
    void explicitReplyKeyboardRowsBecomeTextQuickRepliesInOrderWithoutDuplicates() {
        List<WebQuickReply> replies = resolver.resolve(outgoing("TABLE_BOOKING_COLLECT_DATE", false, false,
                Map.of("replyKeyboardRows", List.of(List.of("Сегодня", "Завтра"), List.of("Завтра", " "), "Отмена", 42))));
        assertThat(replies).extracting(WebQuickReply::value).containsExactly("Сегодня", "Завтра", "Отмена");
        assertThat(replies).allSatisfy(reply -> {
            assertThat(reply.kind()).isEqualTo("text");
            assertThat(reply.id()).startsWith("text-");
        });
    }

    @Test
    void readyForDialogShowsTheConfiguredMainMenu() {
        List<WebQuickReply> replies = resolver.resolve(outgoing("READY_FOR_DIALOG", false, true, Map.of()));
        assertThat(replies).extracting(WebQuickReply::text).containsExactly("Бронь стола", "Меню кухни", "Главное меню");
    }

    @Test
    void otherStatesAndNullsHaveNoButtons() {
        assertThat(resolver.resolve(outgoing("AI_FALLBACK", false, true, Map.of()))).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(new WebQuickReplyResolver("").resolve(outgoing("READY_FOR_DIALOG", false, false, Map.of()))).isEmpty();
    }
}
