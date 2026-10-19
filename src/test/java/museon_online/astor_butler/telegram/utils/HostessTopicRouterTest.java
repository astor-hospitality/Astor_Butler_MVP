package museon_online.astor_butler.telegram.utils;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Which sends get the bookings topic: the hostess chat's, without a topic of their own, and only with the setting. */
class HostessTopicRouterTest {

    private static final String STAFF_CHAT = "-1002768239841";
    private static final InputFile FILE = new InputFile("https://example.org/plan.pdf");

    private final HostessTopicRouter router = new HostessTopicRouter(STAFF_CHAT, "6");

    @Test
    void everySendToTheStaffChatGetsTheBookingsTopic() {
        assertThat(router.route(SendMessage.builder().chatId(STAFF_CHAT).text("card").build()).getMessageThreadId()).isEqualTo(6);
        assertThat(router.route(SendPhoto.builder().chatId(STAFF_CHAT).photo(FILE).build()).getMessageThreadId()).isEqualTo(6);
        assertThat(router.route(SendDocument.builder().chatId(STAFF_CHAT).document(FILE).build()).getMessageThreadId()).isEqualTo(6);
        assertThat(router.route(SendMediaGroup.builder().chatId(STAFF_CHAT)
                .medias(List.of(new InputMediaPhoto("a"), new InputMediaPhoto("b"))).build()).getMessageThreadId()).isEqualTo(6);
        assertThat(router.route(SendVideo.builder().chatId(STAFF_CHAT).video(FILE).build()).getMessageThreadId()).isEqualTo(6);
        assertThat(router.route(SendVoice.builder().chatId(STAFF_CHAT).voice(FILE).build()).getMessageThreadId()).isEqualTo(6);
        assertThat(router.route(SendAudio.builder().chatId(STAFF_CHAT).audio(FILE).build()).getMessageThreadId()).isEqualTo(6);
        assertThat(router.route(SendAnimation.builder().chatId(STAFF_CHAT).animation(FILE).build()).getMessageThreadId()).isEqualTo(6);
    }

    @Test
    void aTopicNamedByTheSenderIsKept() {
        assertThat(router.route(SendMessage.builder().chatId(STAFF_CHAT).messageThreadId(340).text("review").build())
                .getMessageThreadId()).isEqualTo(340);
        assertThat(router.route(SendPhoto.builder().chatId(STAFF_CHAT).messageThreadId(1).photo(FILE).build())
                .getMessageThreadId()).isEqualTo(1);
    }

    @Test
    void otherChatsAreLeftAlone() {
        assertThat(router.route(SendMessage.builder().chatId("1773317437").text("guest").build()).getMessageThreadId()).isNull();
        assertThat(router.route(SendMessage.builder().chatId("-100555").text("system").build()).getMessageThreadId()).isNull();
        assertThat(router.route(SendMessage.builder().chatId(" " + STAFF_CHAT + " ").text("trimmed").build()).getMessageThreadId())
                .isEqualTo(6);
    }

    @Test
    void editsAndCallbackAnswersAreUntouched() {
        EditMessageText edit = EditMessageText.builder().chatId(STAFF_CHAT).messageId(5).text("x").build();
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder().callbackQueryId("q").build();

        assertThat(router.route(edit)).isSameAs(edit);
        assertThat(router.route(answer)).isSameAs(answer);
        assertThat(router.route((SendMessage) null)).isNull();
    }

    @Test
    void aBlankOrBadSettingIsGeneralAsBefore() {
        for (String setting : new String[]{null, "", "  ", "abc", "0", "-6"}) {
            HostessTopicRouter off = new HostessTopicRouter(STAFF_CHAT, setting);
            assertThat(off.route(SendMessage.builder().chatId(STAFF_CHAT).text("card").build()).getMessageThreadId())
                    .as("setting '%s'", setting).isNull();
        }
        assertThat(new HostessTopicRouter("", "6").route(SendMessage.builder().chatId(STAFF_CHAT).text("card").build())
                .getMessageThreadId()).isNull();
        assertThat(HostessTopicRouter.none().route(SendMessage.builder().chatId(STAFF_CHAT).text("card").build())
                .getMessageThreadId()).isNull();
        assertThat(HostessTopicRouter.threadId(" 340 ", "TELEGRAM_FEEDBACK_THREAD_ID")).isEqualTo(340);
    }

    @Test
    void aTestDoubleSenderGetsTheMethodAsItWas() {
        SendPhoto photo = SendPhoto.builder().chatId(STAFF_CHAT).photo(FILE).build();

        assertThat(HostessTopicRouter.route(mock(AbsSender.class), photo).getMessageThreadId()).isNull();
    }

    /**
     * The library's sync uploads ({@code execute(SendPhoto)} and the like) are final, so {@link TelegramBot} cannot route
     * them: each one in the code must pass through {@link HostessTopicRouter#route(AbsSender, org.telegram.telegrambots.meta.api.methods.PartialBotApiMethod)}.
     */
    @Test
    void everyUploadInTheCodeIsRouted() throws IOException {
        Pattern upload = Pattern.compile("\\.execute\\((?:org\\.telegram\\S*\\.)?Send(?:Photo|Document|Video|VideoNote|Voice|Audio|Animation|Sticker)\\.builder\\(\\)");
        List<String> unrouted;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            unrouted = files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        String source = read(path);
                        Stream.Builder<String> found = Stream.builder();
                        Matcher matcher = upload.matcher(source);
                        while (matcher.find()) {
                            found.add(path + ": " + matcher.group());
                        }
                        return found.build();
                    })
                    .toList();
        }
        assertThat(unrouted).isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
