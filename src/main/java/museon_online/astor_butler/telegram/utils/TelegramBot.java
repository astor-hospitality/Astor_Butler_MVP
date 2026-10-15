package museon_online.astor_butler.telegram.utils;

import museon_online.astor_butler.config.TelegramBotConfig;
import museon_online.astor_butler.telegram.adapter.TelegramRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class TelegramBot extends TelegramLongPollingBot {

    private final TelegramRouter telegramRouter;
    private final TelegramBotConfig telegramBotConfig;
    private volatile HostessThreadRouter hostessThreadRouter = new HostessThreadRouter(null, null);

    public TelegramBot(TelegramRouter telegramRouter, TelegramBotConfig telegramBotConfig) {
        super(telegramBotConfig.botOptions());
        this.telegramRouter = telegramRouter;
        this.telegramBotConfig = telegramBotConfig;
    }

    @Autowired
    void configureHostessThread(@Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                @Value("${telegram.booking.hostess-thread-id:}") String hostessThreadId) {
        hostessThreadRouter = new HostessThreadRouter(hostessChatId, hostessThreadId);
    }

    // Every text card, location, media group and the like to the hostess chat passes here (the single choke point
    // of the bot); new messages without a topic go into the configured bookings topic. Edits are not touched.
    @Override
    public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method) throws TelegramApiException {
        hostessThreadRouter.apply(method);
        return super.execute(method);
    }

    @Override
    protected <T extends Serializable, Method extends BotApiMethod<T>> CompletableFuture<T> sendApiMethodAsync(Method method) {
        hostessThreadRouter.apply(method);
        return super.sendApiMethodAsync(method);
    }

    @Override
    public List<Message> execute(SendMediaGroup sendMediaGroup) throws TelegramApiException {
        hostessThreadRouter.apply(sendMediaGroup);
        return super.execute(sendMediaGroup);
    }

    @Override
    public Message execute(SendAnimation sendAnimation) throws TelegramApiException {
        hostessThreadRouter.apply(sendAnimation);
        return super.execute(sendAnimation);
    }

    @Override
    public CompletableFuture<List<Message>> executeAsync(SendMediaGroup sendMediaGroup) {
        hostessThreadRouter.apply(sendMediaGroup);
        return super.executeAsync(sendMediaGroup);
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

}
