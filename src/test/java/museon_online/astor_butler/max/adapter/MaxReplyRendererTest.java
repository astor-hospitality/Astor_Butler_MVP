package museon_online.astor_butler.max.adapter;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.max.client.MaxButton;
import museon_online.astor_butler.max.client.MaxOutgoingMessage;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The MAX rendering of a gateway answer shows the guest the same choices the Telegram router shows. */
class MaxReplyRendererTest {

    private final MaxReplyRenderer renderer = new MaxReplyRenderer();
    private final IncomingMessage incoming = IncomingMessage.max(8_000_000_000_001L, 77L, "привет", null, "Анна",
            null, null, "ru", false, "max:mid.1", Map.of());

    @Test
    void consentStepGetsTheContactButtonAndHtml() {
        OutgoingMessage outgoing = reply("Нажимая кнопку <a href=\"https://x\">политика</a>", BotState.CONSENT_REQUIRED, true, true);

        List<MaxOutgoingMessage> messages = renderer.render(outgoing, true);

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.format()).isEqualTo("html");
            assertThat(message.keyboard()).containsExactly(List.of(MaxButton.requestContact("Согласиться и поделиться контактом")));
        });
    }

    @Test
    void readyForDialogShowsTheGuestMainMenuAsMessageButtons() {
        List<MaxOutgoingMessage> messages = renderer.render(reply("Чем помочь?", BotState.READY_FOR_DIALOG, false, false), true);

        List<List<MaxButton>> keyboard = messages.getFirst().keyboard();
        assertThat(keyboard).hasSize(MaxReplyRenderer.GUEST_MAIN_MENU.size());
        assertThat(keyboard.getFirst()).containsExactly(MaxButton.message("Меню кухни"), MaxButton.message("Бар"));
        assertThat(keyboard).allSatisfy(row -> assertThat(row).allMatch(button -> button.kind() == MaxButton.Kind.MESSAGE));
        assertThat(messages.getFirst().format()).isNull();
    }

    @Test
    void scenarioRowsWinOverTheMainMenu() {
        OutgoingMessage outgoing = reply("Какую бронь изменить?", BotState.READY_FOR_DIALOG, false, false)
                .withMetadata(Map.of("replyKeyboardRows", List.of(List.of("Бронь #12", ""), List.of("Назад"))));

        assertThat(renderer.render(outgoing, true).getFirst().keyboard()).containsExactly(
                List.of(MaxButton.message("Бронь #12")),
                List.of(MaxButton.message("Назад")));
    }

    @Test
    void groupChatsNeverGetTheContactButtonOrTheGuestMenu() {
        assertThat(renderer.render(reply("x", BotState.CONSENT_REQUIRED, false, true), false).getFirst().keyboard()).isEmpty();
        assertThat(renderer.render(reply("x", BotState.READY_FOR_DIALOG, false, false), false).getFirst().keyboard()).isEmpty();
    }

    @Test
    void silentAnswersSendNothing() {
        assertThat(renderer.render(reply("", BotState.READY_FOR_DIALOG, false, false), true)).isEmpty();
        assertThat(renderer.render(null, true)).isEmpty();
    }

    @Test
    void longTextIsSplitOnParagraphsWithTheKeyboardUnderTheLastPart() {
        String paragraph = "а".repeat(2500);
        OutgoingMessage outgoing = reply(paragraph + "\n\n" + paragraph + "\n\n" + "конец", BotState.READY_FOR_DIALOG, false, false);

        List<MaxOutgoingMessage> messages = renderer.render(outgoing, true);

        assertThat(messages).hasSize(2);
        assertThat(messages).allSatisfy(message -> assertThat(message.text().length()).isLessThanOrEqualTo(MaxReplyRenderer.MAX_TEXT_LENGTH));
        assertThat(messages.getFirst().keyboard()).isEmpty();
        assertThat(messages.getLast().keyboard()).isNotEmpty();
        assertThat(String.join("\n\n", messages.stream().map(MaxOutgoingMessage::text).toList()))
                .isEqualTo(outgoing.text());
    }

    @Test
    void aSingleOverlongLineIsCut() {
        List<String> parts = MaxReplyRenderer.split("б".repeat(9000), 4000);

        assertThat(parts).extracting(String::length).containsExactly(4000, 4000, 1000);
    }

    private OutgoingMessage reply(String text, BotState next, boolean html, boolean requestContact) {
        return OutgoingMessage.of(incoming, text, next.name(), html, requestContact, false, false, AdminAlert.none(), List.of());
    }
}
