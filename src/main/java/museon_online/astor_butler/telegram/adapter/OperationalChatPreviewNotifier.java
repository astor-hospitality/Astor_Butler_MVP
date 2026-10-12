package museon_online.astor_butler.telegram.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.config.AstorLinksProperties;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OperationalChatPreviewNotifier {

    private static final String KNOWLEDGE_BASE_URL = "https://auspicious-kryptops-863.notion.site/Astor-Butler-380a7c019f1980d78b68d8bc659c609b?source=copy_link";
    private static final String STAFF_GUIDE_URL = "https://app.notion.com/p/381a7c019f1981b08ca4ed4146e630e4";
    private static final String ADMIN_GUIDE_URL = "https://app.notion.com/p/381a7c019f1981988530d1464d567af4";
    private static final String SYSTEM_GUIDE_URL = "https://app.notion.com/p/382a7c019f198148b78aef491ceee4f6";

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final AstorLinksProperties links;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${astor.startup.operational-preview-enabled:true}")
    private boolean enabled;

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String staffChatId;

    @Value("${telegram.system.chat-id:}")
    private String systemChatId;

    @EventListener(ApplicationReadyEvent.class)
    public void publishOperationalPreviews() {
        if (!telegramEnabled || !enabled) {
            log.debug("Operational chat previews skipped: telegramEnabled={}, enabled={}", telegramEnabled, enabled);
            return;
        }

        TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
        if (telegramBot == null) {
            log.debug("Operational chat previews skipped: TelegramBot is unavailable");
            return;
        }

        for (Map.Entry<String, PreviewCard> entry : previews().entrySet()) {
            publish(entry.getKey(), entry.getValue(), telegramBot);
        }
    }

    private Map<String, PreviewCard> previews() {
        Map<String, PreviewCard> cards = new LinkedHashMap<>();
        cards.put(adminChatId, new PreviewCard("admin", adminPreview()));
        cards.put(staffChatId, new PreviewCard("staff", staffPreview()));
        cards.put(systemChatId, new PreviewCard("system", systemPreview()));
        return cards;
    }

    private void publish(String chatId, PreviewCard card, TelegramBot telegramBot) {
        if (chatId == null || chatId.isBlank() || card == null) {
            return;
        }

        try {
            Message message = telegramBot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(card.text())
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());

            if (message != null && message.getMessageId() != null) {
                pin(chatId, card, telegramBot, message.getMessageId());
            }
        } catch (Exception e) {
            log.warn("Operational {} preview message was not published: {}", card.kind(), e.getMessage());
        }
    }

    private void pin(String chatId, PreviewCard card, TelegramBot telegramBot, Integer messageId) {
        try {
                telegramBot.execute(PinChatMessage.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .disableNotification(true)
                        .build());
        } catch (Exception e) {
            log.warn("Operational {} preview message was published, but pin failed: {}", card.kind(), e.getMessage());
        }
    }

    String adminPreview() {
        return """
                <b>Astor Butler Admin Chat</b>
                Центр ручного контроля, fallback, manager help и анализа сценариев.

                <b>Сюда приходит</b>
                • fallback / recovery
                • просьбы позвать менеджера
                • feedback и жалобы
                • спорные safe-play / payment / auction boundaries
                • admin projection событий

                <b>Правило</b>
                Не запускать guest FSM из этого чата. Решение фиксировать через доменный слой/API.

                <a href="%s">Инструкция администратора</a>
                <a href="%s">FSM Viewer</a>
                <a href="%s">База знаний</a>
                """.formatted(ADMIN_GUIDE_URL, links.getFsmViewer(), KNOWLEDGE_BASE_URL);
    }

    private String staffPreview() {
        return """
                <b>Astor Butler Staff Chat</b>
                Рабочий чат команды: бронь, service requests, safe-play решения и операционные карточки.

                <b>Сюда приходит</b>
                • заявки на бронь с кнопками
                • контекст гостя и последние сообщения
                • отмены/изменения
                • concierge, merch, tip, donation, auction после подтверждения гостя

                <b>Правило</b>
                Подтверждать действия кнопкой/API. Не вести гостевой диалог из staff chat.

                <a href="%s">Инструкция Staff Chat</a>
                <a href="%s">База знаний</a>
                """.formatted(STAFF_GUIDE_URL, KNOWLEDGE_BASE_URL);
    }

    String systemPreview() {
        return """
                <b>Astor Butler System Chat</b>
                Служебный канал наблюдаемости: dialog trace, startup, FSM transitions, action tags, Kafka/outbox и correlation ids.

                <b>Читать как telemetry</b>
                • guest input → app reply в одной карточке
                • previous state → next state
                • action tags
                • Kafka outbox status
                • chat/user/correlation
                • #dialog_* для быстрого поиска диалога одного гостя
                • признаки fallback/recovery

                <b>Правило</b>
                Здесь ничего не подтверждаем и не запускаем guest FSM. Это экран состояния системы.

                <a href="%s">Инструкция System Chat</a>
                <a href="%s">FSM Viewer</a>
                <a href="%s">База знаний</a>
                """.formatted(SYSTEM_GUIDE_URL, links.getFsmViewer(), KNOWLEDGE_BASE_URL);
    }

    private record PreviewCard(String kind, String text) {
    }
}
