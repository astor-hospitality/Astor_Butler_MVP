package museon_online.astor_butler.telegram.voice;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.speech.TextToSpeech;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The Telegram reply for a guest who may not be looking at the screen: a voice note with the whole answer
 * (through the server {@link TextToSpeech}) plus a short text summary with links as buttons, photos when the
 * answer shows some, and a "Подробнее" button that sends the full text on tap.
 *
 * <p>Ordering: the text summary goes first and the voice follows when it is ready; a hands-free chat
 * ({@code /voice on}) gets the voice first. Synthesis runs under {@link TelegramVoiceReplyConfig#timeout()} and
 * never blocks the text; when it fails or is late the guest gets the ordinary full text instead, and the failure
 * is logged at WARN at most once a minute. {@code ASTOR_TELEGRAM_VOICE_REPLIES=off} makes {@link #reply} say
 * "not mine" and the router sends the reply exactly as before.
 */
@Slf4j
@Service
public class TelegramVoiceReplyService {

    static final String CALLBACK_PREFIX = "voice_full:";
    static final String DETAILS_BUTTON = "Подробнее";
    private static final Set<String> VOICE_MIME_TYPES = Set.of("audio/ogg", "audio/mpeg", "audio/mp4");
    private static final int TELEGRAM_TEXT_LIMIT = 4000;
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final TelegramVoiceReplyConfig config;
    private final TextToSpeech textToSpeech;
    private final ReplySummarizer summarizer;
    private final TelegramVoicePreferenceStore store;
    private final Executor executor;
    private final AtomicLong lastWarnAtMs = new AtomicLong(0L);

    @Autowired
    public TelegramVoiceReplyService(TelegramVoiceReplyConfig config, TextToSpeech textToSpeech,
                                     ReplySummarizer summarizer, TelegramVoicePreferenceStore store) {
        this(config, textToSpeech, summarizer, store,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tg-voice-", 0).factory()));
    }

    TelegramVoiceReplyService(TelegramVoiceReplyConfig config, TextToSpeech textToSpeech, ReplySummarizer summarizer,
                              TelegramVoicePreferenceStore store, Executor executor) {
        this.config = config;
        this.textToSpeech = textToSpeech;
        this.summarizer = summarizer;
        this.store = store;
        this.executor = executor;
    }

    /**
     * Sends the reply the voice way when the flag and the chat ask for it.
     *
     * @param textSender the router's ordinary text send (keyboards, reply-to and HTML handling stay there)
     * @return false when nothing was sent and the caller must send the reply as usual
     */
    public boolean reply(IncomingMessage incoming, OutgoingMessage outgoing, AbsSender sender,
                         Consumer<OutgoingMessage> textSender) {
        if (!config.enabled() || incoming == null || outgoing == null || sender == null
                || outgoing.chatId() == null || outgoing.chatId() <= 0
                || outgoing.text() == null || outgoing.text().isBlank()) {
            return false;
        }
        boolean handsFree = handsFree(incoming.telegramUserId());
        if (config.mode() == VoiceRepliesMode.AUTO && !handsFree && !voiceInput(incoming)) {
            return false;
        }
        if (!textToSpeech.configured()) {
            warnRateLimited("TTS provider " + textToSpeech.provider() + " is not configured", null);
            return false;
        }
        if (!VOICE_MIME_TYPES.contains(textToSpeech.mimeType())) {
            warnRateLimited("TTS format " + textToSpeech.mimeType() + " is not a Telegram voice format; set SALUTE_TTS_FORMAT=opus"
                    + " (salute) or YANDEX_TTS_FORMAT=oggopus (yandex)", null);
            return false;
        }
        List<String> chunks = ReplyText.speechChunks(ReplyText.speech(outgoing.text()),
                config.speechChunkChars(), config.speechMaxChars());
        if (chunks.isEmpty()) {
            return false;
        }

        CompletableFuture<List<byte[]>> voice = CompletableFuture.supplyAsync(() -> synthesize(chunks), executor)
                .orTimeout(config.timeout().toMillis(), TimeUnit.MILLISECONDS);

        ReplySummary summary = summarize(outgoing.text());
        long detailsId = summary.complete() ? -1L : saveDetails(outgoing);
        InlineKeyboardMarkup voiceKeyboard = keyboard(summary.links(), detailsId);
        InlineKeyboardMarkup linksOnly = keyboard(summary.links(), -1L);
        OutgoingMessage summaryMessage = withText(outgoing, summary.text().isBlank() ? outgoing.text() : summary.text(),
                summary.text().isBlank() && outgoing.html());
        Long chatId = outgoing.chatId();

        if (handsFree) {
            List<byte[]> notes;
            try {
                notes = voice.join();
            } catch (RuntimeException e) {
                warnRateLimited("voice reply for chat " + chatId + " fell back to text", e);
                textSender.accept(outgoing);
                return true;
            }
            sendVoiceNotes(chatId, notes, voiceKeyboard, sender);
            textSender.accept(summaryMessage);
            sendPhotos(chatId, summary.photos(), sender);
            log.info("📤 [TG] Voice-first reply sent to {}: notes={}, summary={}", chatId, notes.size(), summary.source());
            return true;
        }

        textSender.accept(summaryMessage);
        sendPhotos(chatId, summary.photos(), sender);
        voice.whenComplete((notes, error) -> {
            if (error != null) {
                warnRateLimited("voice reply for chat " + chatId + " fell back to text", error);
                if (!summary.complete()) {
                    sendLongText(chatId, outgoing.text(), outgoing.html(), linksOnly, sender);
                }
                return;
            }
            sendVoiceNotes(chatId, notes, voiceKeyboard, sender);
            log.info("📤 [TG] Voice reply sent to {}: notes={}, summary={}", chatId, notes.size(), summary.source());
        });
        return true;
    }

    /** {@code /voice}, {@code /voice on}, {@code /voice off}: the per-chat hands-free switch. */
    public Optional<String> handleCommand(IncomingMessage incoming) {
        if (incoming == null || incoming.text() == null) {
            return Optional.empty();
        }
        String text = incoming.text().trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.equals("/voice") && !lower.startsWith("/voice ") && !lower.startsWith("/voice@")) {
            return Optional.empty();
        }
        if (incoming.chatId() == null || incoming.chatId() <= 0) {
            return Optional.of("Голосовые ответы настраиваются только в личном чате с ботом.");
        }
        String argument = lower.contains(" ") ? lower.substring(lower.indexOf(' ') + 1).trim() : "";
        Boolean wanted = switch (argument) {
            case "on", "вкл", "да" -> Boolean.TRUE;
            case "off", "выкл", "нет" -> Boolean.FALSE;
            default -> null;
        };
        try {
            if (wanted != null) {
                store.setHandsFree(incoming.telegramUserId(), incoming.chatId(), wanted);
            }
            boolean handsFree = wanted != null ? wanted : handsFree(incoming.telegramUserId());
            return Optional.of(statusText(handsFree));
        } catch (RuntimeException e) {
            log.warn("Voice preference was not saved for chat {}: {}", incoming.chatId(), e.getClass().getSimpleName());
            return Optional.of("Не получилось сохранить настройку голоса. Попробуйте ещё раз чуть позже.");
        }
    }

    /** The "Подробнее" tap: the full answer behind the summary, only in the chat it was written for. */
    public CallbackResult handleCallback(String data, Long chatId, AbsSender sender) {
        if (data == null || !data.startsWith(CALLBACK_PREFIX) || chatId == null || sender == null) {
            return CallbackResult.notHandled();
        }
        long id;
        try {
            id = Long.parseLong(data.substring(CALLBACK_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            return CallbackResult.handled("Ответ уже недоступен");
        }
        Optional<TelegramVoicePreferenceStore.FullReply> full;
        try {
            full = store.findFullReply(id, chatId);
        } catch (RuntimeException e) {
            log.warn("Full reply {} for chat {} could not be read: {}", id, chatId, e.getClass().getSimpleName());
            full = Optional.empty();
        }
        if (full.isEmpty()) {
            return CallbackResult.handled("Ответ уже недоступен");
        }
        sendLongText(chatId, full.get().text(), full.get().html(), null, sender);
        return CallbackResult.handled("Полный ответ отправлен");
    }

    private String statusText(boolean handsFree) {
        String mode = switch (config.mode()) {
            case OFF -> "Голосовые ответы на этом боте пока выключены, настройка сохранена и заработает после включения.";
            case ON -> "Бот отвечает голосом на каждое сообщение.";
            case AUTO -> handsFree
                    ? "Бот отвечает голосом на каждое сообщение в этом чате."
                    : "Бот отвечает голосом, когда вы присылаете голосовое.";
        };
        String state = handsFree
                ? "Режим без экрана включён: сначала голос, затем короткий текст."
                : "Режим без экрана выключен: сначала короткий текст, затем голос.";
        return state + "\n" + mode + "\nПереключить: /voice on или /voice off.";
    }

    private boolean handsFree(Long telegramUserId) {
        try {
            return store.isHandsFree(telegramUserId);
        } catch (RuntimeException e) {
            log.debug("Hands-free lookup failed for {}: {}", telegramUserId, e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean voiceInput(IncomingMessage incoming) {
        if (incoming.payload() == null) {
            return false;
        }
        Object mediaKind = incoming.payload().get("mediaKind");
        return "VOICE".equals(mediaKind) || "AUDIO".equals(mediaKind);
    }

    private List<byte[]> synthesize(List<String> chunks) {
        List<byte[]> notes = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            notes.add(textToSpeech.synthesize(chunk));
        }
        return notes;
    }

    private ReplySummary summarize(String fullText) {
        try {
            return summarizer.summarize(fullText);
        } catch (RuntimeException e) {
            log.warn("Voice summary failed ({}); sending the full text", e.getClass().getSimpleName());
            return new ReplySummary("", ReplyText.links(fullText), List.of(), true, "error");
        }
    }

    private long saveDetails(OutgoingMessage outgoing) {
        try {
            return store.saveFullReply(outgoing.chatId(), outgoing.text(), outgoing.html(), config.detailsTtl());
        } catch (RuntimeException e) {
            log.warn("Full reply for chat {} was not stored: {}", outgoing.chatId(), e.getClass().getSimpleName());
            return -1L;
        }
    }

    private InlineKeyboardMarkup keyboard(List<ReplyText.Link> links, long detailsId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ReplyText.Link link : links.stream().limit(config.maxLinks()).toList()) {
            rows.add(List.of(InlineKeyboardButton.builder().text(link.label()).url(link.url()).build()));
        }
        if (detailsId > 0) {
            rows.add(List.of(InlineKeyboardButton.builder().text(DETAILS_BUTTON).callbackData(CALLBACK_PREFIX + detailsId).build()));
        }
        return rows.isEmpty() ? null : InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void sendVoiceNotes(Long chatId, List<byte[]> notes, InlineKeyboardMarkup keyboard, AbsSender sender) {
        String extension = "audio/mpeg".equals(textToSpeech.mimeType()) ? "mp3" : "audio/mp4".equals(textToSpeech.mimeType()) ? "m4a" : "ogg";
        for (int i = 0; i < notes.size(); i++) {
            byte[] bytes = notes.get(i);
            boolean last = i == notes.size() - 1;
            if (bytes == null || bytes.length == 0 || bytes.length > config.maxVoiceBytes()) {
                warnRateLimited("voice note " + (i + 1) + " for chat " + chatId + " skipped: "
                        + (bytes == null ? 0 : bytes.length) + " bytes", null);
                continue;
            }
            SendVoice.SendVoiceBuilder builder = SendVoice.builder()
                    .chatId(chatId.toString())
                    .voice(new InputFile(new ByteArrayInputStream(bytes), "astor-" + (i + 1) + "." + extension));
            if (last && keyboard != null) {
                builder.replyMarkup(keyboard);
            }
            try {
                sender.execute(builder.build());
            } catch (Exception e) {
                warnRateLimited("sendVoice to chat " + chatId + " failed", e);
                return;
            }
        }
    }

    private void sendPhotos(Long chatId, List<ReplyText.Link> photos, AbsSender sender) {
        for (ReplyText.Link photo : photos.stream().limit(config.maxPhotos()).toList()) {
            try {
                sender.execute(SendPhoto.builder()
                        .chatId(chatId.toString())
                        .photo(new InputFile(photo.url()))
                        .caption(photo.label())
                        .build());
            } catch (Exception e) {
                log.debug("Telegram photo {} was not sent to {}: {}", photo.url(), chatId, e.getClass().getSimpleName());
            }
        }
    }

    private void sendLongText(Long chatId, String text, boolean html, InlineKeyboardMarkup keyboard, AbsSender sender) {
        if (text == null || text.isBlank()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        String rest = text.strip();
        while (rest.length() > TELEGRAM_TEXT_LIMIT) {
            int cut = rest.lastIndexOf('\n', TELEGRAM_TEXT_LIMIT);
            if (cut < TELEGRAM_TEXT_LIMIT / 2) {
                cut = rest.lastIndexOf(' ', TELEGRAM_TEXT_LIMIT);
            }
            if (cut < TELEGRAM_TEXT_LIMIT / 2) {
                cut = TELEGRAM_TEXT_LIMIT;
            }
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        parts.add(rest);
        // HTML that was split may carry an open tag across the boundary; plain text is the safe way to send pieces.
        boolean parseHtml = html && parts.size() == 1;
        for (int i = 0; i < parts.size(); i++) {
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(parseHtml ? parts.get(i) : (html ? ReplyText.plain(parts.get(i)) : parts.get(i)));
            if (parseHtml) {
                builder.parseMode("HTML");
            }
            if (keyboard != null && i == parts.size() - 1) {
                builder.replyMarkup(keyboard);
            }
            try {
                sender.execute(builder.build());
            } catch (Exception e) {
                log.warn("Full text to chat {} was not sent: {}", chatId, e.getClass().getSimpleName());
                return;
            }
        }
    }

    private static OutgoingMessage withText(OutgoingMessage outgoing, String text, boolean html) {
        return new OutgoingMessage(
                outgoing.channel(),
                outgoing.externalUserId(),
                outgoing.chatId(),
                text,
                outgoing.nextState(),
                html,
                outgoing.requestContact(),
                outgoing.removeKeyboard(),
                outgoing.fallback(),
                outgoing.adminAlert(),
                outgoing.actions(),
                outgoing.metadata(),
                outgoing.createdAt()
        );
    }

    /** One WARN per minute for the whole bot; the rest at DEBUG, so a dead TTS does not flood the log per reply. */
    private void warnRateLimited(String what, Throwable error) {
        Throwable root = error;
        while (root instanceof CompletionException && root.getCause() != null) {
            root = root.getCause();
        }
        String reason = root == null ? "" : " (" + root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage()) + ")";
        long now = System.currentTimeMillis();
        long last = lastWarnAtMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnAtMs.compareAndSet(last, now)) {
            log.warn("Telegram voice reply: {}{}", what, reason);
        } else {
            log.debug("Telegram voice reply: {}{}", what, reason);
        }
    }

    public record CallbackResult(boolean handled, String answerText) {
        public static CallbackResult handled(String answerText) {
            return new CallbackResult(true, answerText);
        }

        public static CallbackResult notHandled() {
            return new CallbackResult(false, "");
        }
    }
}
