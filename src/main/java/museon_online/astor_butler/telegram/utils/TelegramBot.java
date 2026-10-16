package museon_online.astor_butler.telegram.utils;

import museon_online.astor_butler.config.TelegramBotConfig;
import museon_online.astor_butler.telegram.adapter.TelegramRouter;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.updateshandlers.SentCallback;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class TelegramBot extends TelegramLongPollingBot {

    private final TelegramRouter telegramRouter;
    private final TelegramBotConfig telegramBotConfig;
    private final HostessTopicRouter topics;

    public TelegramBot(TelegramRouter telegramRouter, TelegramBotConfig telegramBotConfig, HostessTopicRouter topics) {
        super(telegramBotConfig.botOptions());
        this.telegramRouter = telegramRouter;
        this.telegramBotConfig = telegramBotConfig;
        this.topics = topics == null ? HostessTopicRouter.none() : topics;
    }

    @Override
    public String getBotUsername() {
        return telegramBotConfig.getUsername();
    }

    @Override
    public String getBotToken() {
        return telegramBotConfig.getToken();
    }
    @Override
    public void onUpdateReceived(Update update) {
        telegramRouter.handle(update, this);
    }

    /** Where staff-chat messages without a topic go; for the uploads whose {@code execute} the library made final. */
    public HostessTopicRouter topics() {
        return topics;
    }

    // Every send of the bot passes here: a message to the hostess chat without a topic goes to the bookings topic.

    @Override
    public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method) throws TelegramApiException {
        return super.execute(topics.route(method));
    }

    @Override
    public <T extends Serializable, Method extends BotApiMethod<T>> CompletableFuture<T> executeAsync(Method method) throws TelegramApiException {
        return super.executeAsync(topics.route(method));
    }

    @Override
    public <T extends Serializable, Method extends BotApiMethod<T>, Callback extends SentCallback<T>> void executeAsync(Method method, Callback callback) throws TelegramApiException {
        super.executeAsync(topics.route(method), callback);
    }

    @Override
    public List<Message> execute(SendMediaGroup sendMediaGroup) throws TelegramApiException {
        return super.execute(topics.route(sendMediaGroup));
    }

    @Override
    public CompletableFuture<List<Message>> executeAsync(SendMediaGroup sendMediaGroup) {
        return super.executeAsync(topics.route(sendMediaGroup));
    }

    @Override
    public CompletableFuture<Message> executeAsync(SendPhoto sendPhoto) {
        return super.executeAsync(topics.route(sendPhoto));
    }

    @Override
    public CompletableFuture<Message> executeAsync(SendDocument sendDocument) {
        return super.executeAsync(topics.route(sendDocument));
    }
}
