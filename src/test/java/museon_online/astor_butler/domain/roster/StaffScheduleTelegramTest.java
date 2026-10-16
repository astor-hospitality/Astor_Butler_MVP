package museon_online.astor_butler.domain.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Telegram side: which messages are the schedule's, replies into the «График» topic, buttons editing one message. */
class StaffScheduleTelegramTest {

    private static final long CHAT = -1002768239841L;

    private RosterTestDatabase database;
    private StaffScheduleProperties properties;
    private StaffScheduleRepository repository;
    private StaffScheduleServiceTest.FakeVision vision;
    private StaffScheduleTelegram telegram;
    private AbsSender sender;
    private byte[] picture;

    @BeforeEach
    void setUp() throws Exception {
        database = RosterTestDatabase.create();
        database.directory();
        properties = new StaffScheduleProperties();
        properties.setEnabled(true);
        properties.setChatId(CHAT);
        properties.setThreadId(4);
        repository = new StaffScheduleRepository(database.jdbc());
        StaffRoster roster = new StaffRoster(properties, repository,
                Clock.fixed(Instant.parse("2026-10-16T09:00:00Z"), ZoneId.of("UTC")));
        vision = new StaffScheduleServiceTest.FakeVision(StaffScheduleParserTest::fixtureAnswer);
        StaffScheduleService service = new StaffScheduleService(properties, repository, roster, vision);
        try (InputStream input = getClass().getResourceAsStream("/roster/schedule-week.png")) {
            picture = input.readAllBytes();
        }
        // Recognition runs right away instead of on its own thread; the file comes from the fixture, not Telegram.
        telegram = new StaffScheduleTelegram(properties, service, Runnable::run) {
            @Override
            byte[] download(AbsSender ignored, String fileId) {
                assertThat(fileId).isEqualTo("photo-large");
                return picture;
            }
        };
        sender = mock(AbsSender.class);
        Message sent = new Message();
        sent.setMessageId(555);
        when(sender.execute(any(SendMessage.class))).thenReturn(sent);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void aPhotoInTheScheduleTopicIsAnsweredInThatTopicUnderThePicture() throws Exception {
        assertThat(telegram.handle(photo(CHAT, 4), sender)).isTrue();

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(sender).execute(sent.capture());
        SendMessage reply = sent.getValue();
        assertThat(reply.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(reply.getMessageThreadId()).isEqualTo(4);
        assertThat(reply.getReplyToMessageId()).isEqualTo(77);
        assertThat(reply.getParseMode()).isEqualTo("HTML");
        assertThat(reply.getText()).contains("уточните");
        assertThat(((InlineKeyboardMarkup) reply.getReplyMarkup()).getKeyboard()).hasSize(4);
        assertThat(vision.requests).singleElement().satisfies(request -> assertThat(request.mimeType()).isEqualTo("image/jpeg"));
        // The bot's message is remembered as the one the buttons are on.
        assertThat(repository.schedule(1).orElseThrow().replyMessageId()).isEqualTo(555);
    }

    @Test
    void photosElsewhereAreLeftToTheBot() {
        assertThat(telegram.handle(photo(CHAT, 6), sender)).isFalse();     // «Брони» topic
        assertThat(telegram.handle(photo(CHAT, null), sender)).isFalse();  // the general topic
        assertThat(telegram.handle(photo(-100123L, 4), sender)).isFalse(); // another group
        Message words = message(CHAT, 4);
        words.setText("Кто завтра выходит?");
        assertThat(telegram.handle(words, sender)).isFalse();

        properties.setEnabled(false);
        assertThat(telegram.handle(photo(CHAT, 4), sender)).isFalse();

        verifyNoInteractions(sender);
        assertThat(vision.requests).isEmpty();
    }

    @Test
    void smenaIsAnsweredInTheTopicItWasAskedIn() throws Exception {
        Message command = message(CHAT, 340);
        command.setText("/смена");

        assertThat(telegram.handle(command, sender)).isTrue();

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(sender).execute(sent.capture());
        assertThat(sent.getValue().getMessageThreadId()).isEqualTo(340);
        assertThat(sent.getValue().getText()).contains("Подтверждённого графика на 16.10 нет");
    }

    @Test
    void aButtonAnswersTheCallbackAndEditsThePressedMessage() throws Exception {
        telegram.handle(photo(CHAT, 4), sender);
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(sender).execute(sent.capture());
        String data = ((InlineKeyboardMarkup) sent.getValue().getReplyMarkup()).getKeyboard().get(0).get(0).getCallbackData();

        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb-1");
        callback.setData(data);
        callback.setFrom(user());
        callback.setMessage(message(CHAT, 4));
        callback.getMessage().setMessageId(555);

        assertThat(telegram.handleCallback(callback, sender)).isTrue();

        ArgumentCaptor<BotApiMethod> calls = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(sender, atLeastOnce()).execute(calls.capture());
        List<BotApiMethod> methods = calls.getAllValues();
        AnswerCallbackQuery answer = (AnswerCallbackQuery) methods.stream().filter(AnswerCallbackQuery.class::isInstance).findFirst().orElseThrow();
        assertThat(answer.getText()).isEqualTo("«Глеп Макетов» — Глеб Макетов.");
        EditMessageText edit = (EditMessageText) methods.stream().filter(EditMessageText.class::isInstance).findFirst().orElseThrow();
        assertThat(edit.getMessageId()).isEqualTo(555);
        assertThat(edit.getText()).doesNotContain("«Глеп Макетов»");
        assertThat(edit.getReplyMarkup().getKeyboard()).hasSize(3);
    }

    @Test
    void otherButtonsAreNotTheSchedules() {
        CallbackQuery callback = new CallbackQuery();
        callback.setData("seated:12");
        callback.setMessage(message(CHAT, 4));

        assertThat(telegram.handleCallback(callback, sender)).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void theLargestPhotoOrAnImageFileIsTaken() {
        Message photo = photo(CHAT, 4);
        assertThat(StaffScheduleTelegram.image(photo).fileId()).isEqualTo("photo-large");

        Message screenshot = message(CHAT, 4);
        Document png = new Document();
        png.setFileId("file-png");
        png.setMimeType("image/PNG");
        png.setFileSize(120_000L);
        screenshot.setDocument(png);
        assertThat(StaffScheduleTelegram.image(screenshot)).isEqualTo(new StaffScheduleTelegram.Image("file-png", "image/png", 120_000L));

        Message pdf = message(CHAT, 4);
        Document document = new Document();
        document.setFileId("file-pdf");
        document.setMimeType("application/pdf");
        pdf.setDocument(document);
        assertThat(StaffScheduleTelegram.image(pdf)).isNull();
    }

    @Test
    void aFileOverTheLimitIsNotReadToTheEnd() throws Exception {
        assertThat(StaffScheduleTelegram.readLimited(new ByteArrayInputStream(new byte[10]), 10)).hasSize(10);
        assertThat(StaffScheduleTelegram.readLimited(new ByteArrayInputStream(new byte[11]), 10)).isNull();
    }

    @Test
    void whoPressedIsTheirNameAndUsername() {
        assertThat(StaffScheduleTelegram.who(user())).isEqualTo("Маша (@masha)");
        assertThat(StaffScheduleTelegram.who(null)).isNull();
    }

    private static Message photo(long chatId, Integer thread) {
        Message message = message(chatId, thread);
        message.setPhoto(List.of(photoSize("photo-small", 90, 60), photoSize("photo-large", 1280, 853), photoSize("photo-mid", 320, 213)));
        return message;
    }

    private static PhotoSize photoSize(String fileId, int width, int height) {
        PhotoSize size = new PhotoSize();
        size.setFileId(fileId);
        size.setWidth(width);
        size.setHeight(height);
        size.setFileSize(width * height / 10);
        return size;
    }

    private static Message message(long chatId, Integer thread) {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setType("supergroup");
        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(77);
        message.setFrom(user());
        if (thread != null) {
            message.setIsTopicMessage(true);
            message.setMessageThreadId(thread);
        }
        return message;
    }

    private static User user() {
        User user = new User();
        user.setId(42L);
        user.setFirstName("Маша");
        user.setUserName("masha");
        user.setIsBot(false);
        return user;
    }
}
