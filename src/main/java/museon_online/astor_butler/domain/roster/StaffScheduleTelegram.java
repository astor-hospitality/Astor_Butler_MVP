package museon_online.astor_butler.domain.roster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Telegram side of the schedule: a photo or an image file in the «График» topic is recognised on a thread of its own
 * (a vision call takes seconds; the bot's single update thread does not wait), the reply goes to the same topic as a
 * reply to the picture, the buttons edit that one message, and /смена answers wherever in the staff group it was
 * typed. Photos in other topics and other chats are left to the bot as before.
 *
 * <p>The picture lives in memory only until the model answers: it is not written to disk or archived.
 */
@Service
@Slf4j
public class StaffScheduleTelegram implements DisposableBean {

    private static final List<String> IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final StaffScheduleProperties properties;
    private final StaffScheduleService service;
    private final Executor executor;
    private final ExecutorService ownExecutor;

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.bot.proxy.type:NO_PROXY}")
    private String proxyType;

    @Value("${telegram.bot.proxy.host:}")
    private String proxyHost;

    @Value("${telegram.bot.proxy.port:0}")
    private int proxyPort;

    @Value("${telegram.voice.download-timeout-ms:15000}")
    private int downloadTimeoutMs = 15000;

    @Autowired
    public StaffScheduleTelegram(StaffScheduleProperties properties, StaffScheduleService service) {
        this(properties, service, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "staff-schedule");
            thread.setDaemon(true);
            return thread;
        }));
    }

    StaffScheduleTelegram(StaffScheduleProperties properties, StaffScheduleService service, Executor executor) {
        this.properties = properties;
        this.service = service;
        this.executor = executor;
        this.ownExecutor = executor instanceof ExecutorService own ? own : null;
    }

    /** True when the message was the schedule's (a picture in the topic, /смена) and has been taken care of. */
    public boolean handle(Message message, AbsSender sender) {
        if (message == null || sender == null || !properties.isScheduleChat(message.getChatId())) {
            return false;
        }
        Integer thread = Boolean.TRUE.equals(message.getIsTopicMessage()) ? message.getMessageThreadId() : null;
        if (message.hasText() && service.isShiftCommand(message.getText())) {
            String answer;
            try {
                answer = service.shiftCommand(message.getText());
            } catch (RuntimeException e) {
                log.warn("Staff schedule: /смена failed: {}", e.toString());
                answer = "Не получилось ответить, попробуйте ещё раз.";
            }
            send(sender, message.getChatId(), thread, message.getMessageId(), new StaffScheduleService.Reply(answer, List.of()));
            return true;
        }
        if (!properties.isScheduleTopic(message.getChatId(), thread)) {
            return false;
        }
        Image image = image(message);
        if (image == null) {
            return false;
        }
        String postedBy = who(message.getFrom());
        try {
            executor.execute(() -> recognize(message.getChatId(), thread, message.getMessageId(), image, postedBy, sender));
        } catch (RejectedExecutionException e) {
            log.warn("Staff schedule: the recognition queue refused a picture");
        }
        return true;
    }

    /** True when the button was one of the schedule's; the callback is answered here. */
    public boolean handleCallback(CallbackQuery callback, AbsSender sender) {
        if (callback == null || callback.getData() == null || !callback.getData().startsWith(StaffScheduleService.CALLBACK_PREFIX)
                || !properties.isEnabled()) {
            return false;
        }
        Message message = callback.getMessage();
        Long chatId = message == null ? null : message.getChatId();
        StaffScheduleService.CallbackResult result;
        try {
            result = service.callback(callback.getData(), chatId, who(callback.getFrom()));
        } catch (RuntimeException e) {
            log.warn("Staff schedule: a button failed: {}", e.toString());
            result = new StaffScheduleService.CallbackResult(true, "Не получилось, попробуйте ещё раз.", null);
        }
        if (!result.handled()) {
            return false;
        }
        execute(sender, AnswerCallbackQuery.builder()
                .callbackQueryId(callback.getId())
                .text(result.answerText() == null ? "" : cut(result.answerText(), 190))
                .showAlert(false)
                .build());
        if (result.edit() != null && message != null) {
            EditMessageText.EditMessageTextBuilder edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(message.getMessageId())
                    .text(result.edit().text())
                    .parseMode("HTML");
            InlineKeyboardMarkup keyboard = keyboard(result.edit());
            if (keyboard != null) {
                edit.replyMarkup(keyboard);
            }
            execute(sender, edit.build());
        }
        return true;
    }

    void recognize(Long chatId, Integer thread, Integer messageId, Image image, String postedBy, AbsSender sender) {
        StaffScheduleService.Reply reply;
        Long scheduleId = null;
        try {
            byte[] bytes = image.size() != null && image.size() > properties.getMaxImageBytes()
                    ? null : download(sender, image.fileId());
            if (bytes == null) {
                reply = new StaffScheduleService.Reply(StaffScheduleTexts.TOO_LARGE, List.of());
            } else {
                StaffScheduleService.Recognition recognition =
                        service.recognize(bytes, image.mimeType(), chatId, messageId, postedBy).orElse(null);
                if (recognition == null) {
                    return;
                }
                scheduleId = recognition.scheduleId();
                reply = recognition.reply();
            }
        } catch (Exception e) {
            log.warn("Staff schedule: recognition failed: {}", e.getClass().getSimpleName());
            reply = new StaffScheduleService.Reply(StaffScheduleTexts.FAILED, List.of());
        }
        Message sent = send(sender, chatId, thread, messageId, reply);
        if (sent != null && scheduleId != null) {
            service.replySent(scheduleId, sent.getMessageId());
        }
    }

    record Image(String fileId, String mimeType, Long size) {
    }

    /** The largest version of a photo, or an image sent as a file (a screenshot without compression). */
    static Image image(Message message) {
        if (message.hasPhoto() && message.getPhoto() != null && !message.getPhoto().isEmpty()) {
            PhotoSize largest = message.getPhoto().stream()
                    .max(Comparator.comparingLong(photo -> (long) nz(photo.getWidth()) * nz(photo.getHeight())))
                    .orElseThrow();
            return new Image(largest.getFileId(), "image/jpeg", largest.getFileSize() == null ? null : largest.getFileSize().longValue());
        }
        Document document = message.getDocument();
        if (document != null && document.getMimeType() != null
                && IMAGE_TYPES.contains(document.getMimeType().toLowerCase(Locale.ROOT))) {
            return new Image(document.getFileId(), document.getMimeType().toLowerCase(Locale.ROOT), document.getFileSize());
        }
        return null;
    }

    /** The picture's bytes; null when it is larger than the limit. */
    byte[] download(AbsSender sender, String fileId) throws Exception {
        org.telegram.telegrambots.meta.api.objects.File file = sender.execute(GetFile.builder().fileId(fileId).build());
        if (file == null || file.getFilePath() == null || file.getFilePath().isBlank()) {
            throw new IllegalStateException("Telegram file path is empty");
        }
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException("Telegram bot token is empty");
        }
        URLConnection connection = URI.create(file.getFileUrl(botToken)).toURL().openConnection(proxy());
        int timeout = Math.max(1000, downloadTimeoutMs);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        try (InputStream input = connection.getInputStream()) {
            return readLimited(input, properties.getMaxImageBytes());
        }
    }

    private Proxy proxy() {
        if (proxyType == null || proxyHost == null || proxyHost.isBlank() || proxyPort <= 0) {
            return Proxy.NO_PROXY;
        }
        Proxy.Type type = switch (proxyType.trim().toUpperCase(Locale.ROOT).replace("-", "_")) {
            case "HTTP" -> Proxy.Type.HTTP;
            case "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS;
            default -> Proxy.Type.DIRECT;
        };
        return type == Proxy.Type.DIRECT ? Proxy.NO_PROXY
                : new Proxy(type, InetSocketAddress.createUnresolved(proxyHost.trim(), proxyPort));
    }

    private Message send(AbsSender sender, Long chatId, Integer thread, Integer replyTo, StaffScheduleService.Reply reply) {
        SendMessage.SendMessageBuilder message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(reply.text())
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .allowSendingWithoutReply(true);
        if (thread != null) {
            message.messageThreadId(thread);
        }
        if (replyTo != null) {
            message.replyToMessageId(replyTo);
        }
        InlineKeyboardMarkup keyboard = keyboard(reply);
        if (keyboard != null) {
            message.replyMarkup(keyboard);
        }
        try {
            return sender.execute(message.build());
        } catch (Exception e) {
            log.warn("Staff schedule: cannot send to the staff chat: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static InlineKeyboardMarkup keyboard(StaffScheduleService.Reply reply) {
        if (reply.buttons() == null || reply.buttons().isEmpty()) {
            return null;
        }
        return InlineKeyboardMarkup.builder()
                .keyboard(reply.buttons().stream()
                        .map(row -> row.stream()
                                .map(button -> InlineKeyboardButton.builder().text(button.label()).callbackData(button.data()).build())
                                .toList())
                        .toList())
                .build();
    }

    private static void execute(AbsSender sender, org.telegram.telegrambots.meta.api.methods.BotApiMethod<?> method) {
        try {
            sender.execute(method);
        } catch (Exception e) {
            log.warn("Staff schedule: Telegram call {} failed: {}", method.getMethod(), e.getClass().getSimpleName());
        }
    }

    /** «Даша (@dasha)» — who posted or pressed, as the staff commands record it. */
    static String who(User user) {
        if (user == null) {
            return null;
        }
        String name = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String username = user.getUserName() == null || user.getUserName().isBlank() ? null : "@" + user.getUserName().trim();
        if (name.isEmpty()) {
            return username == null ? "id " + user.getId() : username;
        }
        return username == null ? name : name + " (" + username + ")";
    }

    private static long nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void destroy() {
        if (ownExecutor != null) {
            ownExecutor.shutdownNow();
        }
    }

    /** Null as soon as the stream runs past the limit: a huge file is not read to the end. */
    static byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                return null;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
