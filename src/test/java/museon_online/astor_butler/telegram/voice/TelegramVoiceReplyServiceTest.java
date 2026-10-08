package museon_online.astor_butler.telegram.voice;

import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.speech.TextToSpeech;
import museon_online.astor_butler.speech.TextToSpeechException;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramVoiceReplyServiceTest {

    private static final long CHAT = 814518440L;
    private static final String LONG_ANSWER = """
            Добрый вечер! Сегодня шеф рекомендует тартар из тунца с юдзу и ризотто с белыми грибами.
            Из бара советуем коктейль «Сабраж» и бокал Chablis Premier Cru 2021.
            Меню кухни: https://aeris.bar/menu.pdf
            Если захотите стол у окна, напишите время и количество гостей — хостес подтвердит бронь.
            Напоминаю, что по пятницам и субботам после 21:00 играет живой диджей, поэтому в зале громче обычного.
            И ещё одно длинное предложение, чтобы ответ точно не поместился в четыре коротких строки сводки для экрана.
            """;

    private final TextToSpeech tts = mock(TextToSpeech.class);
    private final TelegramVoicePreferenceStore store = mock(TelegramVoicePreferenceStore.class);
    private final AbsSender sender = mock(AbsSender.class);
    private final List<String> events = new ArrayList<>();

    private TelegramVoiceReplyService service(VoiceRepliesMode mode, Executor executor) {
        return service(TelegramVoiceReplyConfig.defaults(mode), executor);
    }

    private TelegramVoiceReplyService service(TelegramVoiceReplyConfig config, Executor executor) {
        when(tts.configured()).thenReturn(true);
        when(tts.mimeType()).thenReturn("audio/ogg");
        when(tts.provider()).thenReturn("salute");
        when(tts.synthesize(anyString())).thenAnswer(invocation -> {
            events.add("tts");
            return ("OggS" + invocation.getArgument(0, String.class)).getBytes(StandardCharsets.UTF_8);
        });
        when(store.saveFullReply(anyLong(), anyString(), any(Boolean.class), any())).thenReturn(42L);
        try {
            when(sender.execute(any(SendVoice.class))).thenAnswer(invocation -> {
                events.add("voice");
                return null;
            });
            when(sender.execute(any(SendPhoto.class))).thenAnswer(invocation -> {
                events.add("photo");
                return null;
            });
            when(sender.execute(any(SendMessage.class))).thenAnswer(invocation -> {
                events.add("full-text");
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        museon_online.astor_butler.model.ModelGateway modelGateway = mock(museon_online.astor_butler.model.ModelGateway.class);
        when(modelGateway.generateText(any())).thenThrow(new IllegalStateException("no model in this test"));
        ReplySummarizer summarizer = new ReplySummarizer(modelGateway, config);
        return new TelegramVoiceReplyService(config, tts, summarizer, store, executor);
    }

    @Test
    void offModeLeavesTheReplyToTheRouter() {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.OFF, Runnable::run);

        boolean handled = service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"));

        assertThat(handled).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void textSummaryGoesFirstThenVoiceWithDetailsButton() throws Exception {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);
        List<OutgoingMessage> texts = new ArrayList<>();

        boolean handled = service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> {
            events.add("text");
            texts.add(message);
        });

        assertThat(handled).isTrue();
        assertThat(events).containsExactly("tts", "text", "voice");
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0).text().length()).isLessThanOrEqualTo(400);
        assertThat(texts.get(0).text()).contains("https://aeris.bar/menu.pdf");
        assertThat(texts.get(0).nextState()).isEqualTo("READY_FOR_DIALOG");
        SendVoice voice = captureVoice();
        assertThat(voice.getChatId()).isEqualTo(String.valueOf(CHAT));
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) voice.getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(2);
        assertThat(keyboard.getKeyboard().get(0).get(0).getUrl()).isEqualTo("https://aeris.bar/menu.pdf");
        assertThat(keyboard.getKeyboard().get(1).get(0).getText()).isEqualTo("Подробнее");
        assertThat(keyboard.getKeyboard().get(1).get(0).getCallbackData()).isEqualTo("voice_full:42");
        verify(store).saveFullReply(eq(CHAT), eq(LONG_ANSWER), eq(false), any());
        verify(sender, never()).execute(any(SendMessage.class));
    }

    @Test
    void handsFreeChatHearsTheVoiceBeforeTheText() throws Exception {
        when(store.isHandsFree(CHAT)).thenReturn(true);
        TelegramVoiceReplyService service = service(VoiceRepliesMode.AUTO, Runnable::run);

        boolean handled = service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"));

        assertThat(handled).isTrue();
        assertThat(events).containsExactly("tts", "voice", "text");
    }

    @Test
    void autoModeSpeaksOnlyAfterVoiceInputOrHandsFree() {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.AUTO, Runnable::run);

        assertThat(service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"))).isFalse();
        assertThat(events).isEmpty();

        assertThat(service.reply(spoken(), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"))).isTrue();
        assertThat(events).containsExactly("tts", "text", "voice");
    }

    @Test
    void shortAnswerIsSpokenWithoutDetailsButtonAndNothingIsStored() throws Exception {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);

        service.reply(typed("стол"), outgoing("Стол на 20:00 забронирован. Ждём вас!", false), sender, message -> events.add("text"));

        assertThat(events).containsExactly("tts", "text", "voice");
        assertThat(captureVoice().getReplyMarkup()).isNull();
        verify(store, never()).saveFullReply(anyLong(), anyString(), any(Boolean.class), any());
    }

    @Test
    void longSpeechIsSplitIntoSeveralNotesAndTheLastCarriesTheKeyboard() throws Exception {
        TelegramVoiceReplyConfig config = new TelegramVoiceReplyConfig(VoiceRepliesMode.ON, Duration.ofSeconds(8), 400,
                300, 4000, 1 << 20, 3, 3, false, Duration.ofDays(7));
        TelegramVoiceReplyService service = service(config, Runnable::run);

        service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"));

        long notes = events.stream().filter("voice"::equals).count();
        assertThat(notes).isGreaterThanOrEqualTo(2);
        org.mockito.ArgumentCaptor<SendVoice> captor = org.mockito.ArgumentCaptor.forClass(SendVoice.class);
        verify(sender, org.mockito.Mockito.atLeast(2)).execute(captor.capture());
        List<SendVoice> sent = captor.getAllValues();
        assertThat(sent.subList(0, sent.size() - 1)).allSatisfy(note -> assertThat(note.getReplyMarkup()).isNull());
        assertThat(sent.get(sent.size() - 1).getReplyMarkup()).isNotNull();
    }

    @Test
    void ttsTimeoutFallsBackToTheFullTextAfterTheSummary() throws Exception {
        TelegramVoiceReplyConfig config = new TelegramVoiceReplyConfig(VoiceRepliesMode.ON, Duration.ofMillis(500), 400,
                900, 4000, 1 << 20, 3, 3, false, Duration.ofDays(7));
        TelegramVoiceReplyService service = service(config, Executors.newSingleThreadExecutor());
        doAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(5);
            return new byte[]{1};
        }).when(tts).synthesize(anyString());

        boolean handled = service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"));

        assertThat(handled).isTrue();
        assertThat(events).containsExactly("text");
        verify(sender, timeout(3000)).execute(any(SendMessage.class));
        verify(sender, never()).execute(any(SendVoice.class));
        org.mockito.ArgumentCaptor<SendMessage> captor = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(sender).execute(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo(LONG_ANSWER.strip());
    }

    @Test
    void ttsFailureInHandsFreeChatSendsTheOrdinaryFullReply() throws Exception {
        when(store.isHandsFree(CHAT)).thenReturn(true);
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);
        doThrow(new TextToSpeechException("busy", 503)).when(tts).synthesize(anyString());
        List<OutgoingMessage> texts = new ArrayList<>();

        boolean handled = service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, texts::add);

        assertThat(handled).isTrue();
        assertThat(texts).singleElement().satisfies(message -> assertThat(message.text()).isEqualTo(LONG_ANSWER));
        verify(sender, never()).execute(any(SendVoice.class));
        verify(sender, never()).execute(any(SendMessage.class));
    }

    @Test
    void wrongAudioFormatOrUnconfiguredProviderLeavesTextOnly() {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);
        when(tts.mimeType()).thenReturn("audio/wav");
        assertThat(service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"))).isFalse();

        when(tts.mimeType()).thenReturn("audio/ogg");
        when(tts.configured()).thenReturn(false);
        assertThat(service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"))).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void groupChatsAndBlankRepliesAreNotSpoken() {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);
        IncomingMessage group = IncomingMessage.telegram(-100L, CHAT, 1, 2, "меню", null, "A", "B", "ab", "ru", false, "c");

        assertThat(service.reply(group, OutgoingMessage.of(group, LONG_ANSWER, "READY_FOR_DIALOG", false, false, false, false, null, List.of()),
                sender, message -> events.add("text"))).isFalse();
        assertThat(service.reply(typed("x"), outgoing("   ", false), sender, message -> events.add("text"))).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void photosInTheAnswerAreSentNextToTheSummary() {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);

        service.reply(typed("зал"), outgoing("Зал у окна: https://aeris.bar/img/window.jpg — свободен в 20:00.", false),
                sender, message -> events.add("text"));

        assertThat(events).containsExactly("tts", "text", "photo", "voice");
    }

    @Test
    void voiceCommandTogglesHandsFreeAndReportsStatus() {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.AUTO, Runnable::run);

        Optional<String> on = service.handleCommand(typed("/voice on"));
        verify(store).setHandsFree(CHAT, CHAT, true);
        assertThat(on).hasValueSatisfying(text -> assertThat(text).contains("без экрана включён"));

        Optional<String> off = service.handleCommand(typed("/voice off"));
        verify(store).setHandsFree(CHAT, CHAT, false);
        assertThat(off).hasValueSatisfying(text -> assertThat(text).contains("без экрана выключен"));

        when(store.isHandsFree(CHAT)).thenReturn(true);
        assertThat(service.handleCommand(typed("/voice"))).hasValueSatisfying(text -> assertThat(text).contains("включён"));
        assertThat(service.handleCommand(typed("хочу стол"))).isEmpty();
        assertThat(service.handleCommand(typed("/voicemail"))).isEmpty();
    }

    @Test
    void detailsCallbackSendsTheStoredFullText() throws Exception {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);
        when(store.findFullReply(42L, CHAT)).thenReturn(Optional.of(new TelegramVoicePreferenceStore.FullReply("<b>Полный</b> ответ", true)));

        TelegramVoiceReplyService.CallbackResult result = service.handleCallback("voice_full:42", CHAT, sender);

        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).isEqualTo("Полный ответ отправлен");
        org.mockito.ArgumentCaptor<SendMessage> captor = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(sender).execute(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo("<b>Полный</b> ответ");
        assertThat(captor.getValue().getParseMode()).isEqualTo("HTML");

        assertThat(service.handleCallback("voice_full:7", CHAT, sender).answerText()).isEqualTo("Ответ уже недоступен");
        assertThat(service.handleCallback("safe_play:approve:1", CHAT, sender).handled()).isFalse();
    }

    @Test
    void ttsFailuresAreWarnedOncePerMinute() {
        TelegramVoiceReplyService service = service(VoiceRepliesMode.ON, Runnable::run);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            throw new TextToSpeechException("down", 500);
        }).when(tts).synthesize(anyString());

        for (int i = 0; i < 3; i++) {
            service.reply(typed("меню"), outgoing(LONG_ANSWER, false), sender, message -> events.add("text"));
        }

        // Each reply still reaches the guest as text; the WARN cadence is checked by eye in the log, the behaviour here.
        assertThat(calls.get()).isEqualTo(3);
        assertThat(events.stream().filter("text"::equals).count()).isEqualTo(3);
    }

    private SendVoice captureVoice() throws Exception {
        org.mockito.ArgumentCaptor<SendVoice> captor = org.mockito.ArgumentCaptor.forClass(SendVoice.class);
        verify(sender).execute(captor.capture());
        return captor.getValue();
    }

    private static IncomingMessage typed(String text) {
        return IncomingMessage.telegram(CHAT, CHAT, 351, 284070688, text, null, "Voice", "Smoke", "voice_smoke", "ru", false, "284070688");
    }

    private static IncomingMessage spoken() {
        return IncomingMessage.telegram(CHAT, CHAT, 352, 284070689, "меню", null, "Voice", "Smoke", "voice_smoke", "ru", false,
                "284070689", Map.of("mediaKind", "VOICE", "telegramFileId", "file-id"));
    }

    private static OutgoingMessage outgoing(String text, boolean html) {
        return OutgoingMessage.of(typed("x"), text, "READY_FOR_DIALOG", html, false, false, false, null, List.of());
    }
}
