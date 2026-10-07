package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import museon_online.astor_butler.domain.telegram.TelegramGuestContextRepository;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TableReservationNotificationService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Yekaterinburg"));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.of("Asia/Yekaterinburg"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.of("Asia/Yekaterinburg"));

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final TableReservationPendingIntentService pendingIntentService;
    private final TelegramGuestContextRepository guestContextRepository;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled;

    @Value("${telegram.booking.manager-chat-id:876857557}")
    private String defaultManagerChatId;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String defaultHostessChatId;

    /**
     * @param externalSync outcome of writing the order to the restaurant's own system; the card
     *                     tells the hostess what is already there and what she has to enter herself
     */
    public void notifyHostessApprovalRequest(TableReservationOrder order, ExternalReservationResult externalSync) {
        send(
                hostessChatId(order),
                hostessApprovalRequestText(order) + externalSyncLine(order, externalSync),
                approvalKeyboard(order.id()),
                "hostess-approval-request"
        );
    }

    public void notifyHostessExternalCancelFailed(TableReservationOrder order) {
        send(hostessChatId(order), """
                <b>Saby:</b> бронь #%s не снялась в Presto автоматически. Проверьте и отмените её там вручную.
                """.formatted(order.id()), null, "hostess-external-cancel-failed");
    }

    public void notifyHostessConfirmed(TableReservationOrder order) {
        String chatId = hostessChatId(order);
        send(chatId, hostessText(order), null, "hostess");
    }

    public void notifyHostessAcknowledged(TableReservationOrder order) {
        send(hostessChatId(order), """
                <b>Принял.</b> Бронь #%s подтверждена, гостю отправлен красивый ордер.
                """.formatted(order.id()), null, "hostess-ack");
    }

    public void notifyHostessRejected(TableReservationOrder order) {
        send(hostessChatId(order), """
                <b>Принял.</b> Бронь #%s отменена, гостю отправлен вежливый отказ с предложением выбрать другой вариант.
                """.formatted(order.id()), null, "hostess-reject-ack");
    }

    public void notifyHostessGuestCancelled(TableReservationOrder order) {
        send(hostessChatId(order), """
                <b>Гость отменил бронь стола</b>

                <b>Заказ:</b> #%s
                <b>Гость:</b> %s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Телефон:</b> %s

                Hold освобожден, статус заявки: <b>CANCELLED</b>.
                """.formatted(
                order.id(),
                escape(blank(order.guestName(), "гость из Telegram")),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                escape(blank(order.guestPhone(), "не указан"))
        ), null, "hostess-guest-cancelled");
    }

    public void notifyGuestConfirmed(TableReservationOrder order) {
        if (order.chatId() == null) {
            return;
        }
        send(order.chatId().toString(), guestConfirmedText(order), guestMainMenuKeyboard(), "guest-confirmed");
        pendingIntentService.deliverAfterConfirmation(order);
    }

    public void notifyGuestRejected(TableReservationOrder order) {
        notifyGuestRejected(order, List.of());
    }

    public void notifyGuestRejected(TableReservationOrder order, List<VenueTable> alternatives) {
        if (order.chatId() == null) {
            return;
        }
        send(order.chatId().toString(), guestRejectedText(order, alternatives), guestMainMenuKeyboard(), "guest-rejected");
    }

    public void notifyGuestCancelled(TableReservationOrder order) {
        if (order.chatId() == null) {
            return;
        }
        send(order.chatId().toString(), guestCancelledText(order), guestMainMenuKeyboard(), "guest-cancelled");
    }

    private void send(String chatId, String text, ReplyKeyboard keyboard, String target) {
        if (!telegramEnabled || !notificationsEnabled || chatId == null || chatId.isBlank()) {
            log.debug("Table reservation Telegram notification skipped: target={}, botEnabled={}, notificationsEnabled={}, chatConfigured={}",
                    target,
                    telegramEnabled,
                    notificationsEnabled,
                    chatId != null && !chatId.isBlank());
            return;
        }

        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                log.debug("Table reservation Telegram notification skipped: bot bean is not available");
                return;
            }
            telegramBot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
        } catch (Exception e) {
            log.warn("Table reservation Telegram notification failed: target={}, orderId={}, reason={}",
                    target,
                    text.contains("#") ? "included" : "unknown",
                    e.getMessage());
        }
    }

    private String hostessApprovalRequestText(TableReservationOrder order) {
        return """
                <b>Новая заявка на бронь стола</b>

                <b>Заказ:</b> #%s
                <b>Гость:</b> %s
                <b>Telegram:</b> chat %s / user %s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s
                <b>Телефон:</b> %s
                <b>Зона/пожелание:</b> %s

                <b>Исходный запрос гостя</b>
                <blockquote>%s</blockquote>

                <b>Контекст для хостес</b>
                %s

                <b>Последние сообщения гостя</b>
                %s

                <b>Статус:</b> %s
                Выберите действие кнопкой ниже.
                """.formatted(
                order.id(),
                escape(blank(order.guestName(), "гость из Telegram")),
                escape(text(order.chatId())),
                escape(text(order.telegramUserId())),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize(),
                escape(blank(order.guestPhone(), "не указан")),
                escape(seatingPreference(order)),
                escape(blank(order.guestComment(), "нет")),
                escape(hostessContext(order)),
                recentMessages(order),
                escape(humanStatus(order.status()))
        );
    }

    /** Empty while the restaurant's system is switched off, so the card stays as it was. */
    String externalSyncLine(TableReservationOrder order, ExternalReservationResult sync) {
        if (sync == null || !sync.providerConfigured()) {
            return "";
        }
        if (TableReservationService.EXTERNAL_CHANGE_SYNCED.equals(sync.status())) {
            return "\n<b>Saby:</b> изменение уже в Presto — время и число гостей обновлены. "
                    + "Стол в Presto сохранён: сверьте его с заявкой и при необходимости измените вручную.\n";
        }
        if (sync.created()) {
            return "\n<b>Saby:</b> бронь уже создана в Presto без стола — посадите её там на стол из заявки. "
                    + "Если нажмёте «Нет», она снимется в Saby автоматически.\n";
        }
        String instruction = switch (sync.status() == null ? "" : sync.status()) {
            case TableReservationService.EXTERNAL_CHANGE_NOT_SYNCED ->
                    "изменение не попало в Presto. Поправьте бронь там вручную.";
            case "PROVIDER_RESULT_UNKNOWN" ->
                    "Saby не ответил, создана ли бронь. Найдите в Presto бронь с пометкой «Astor Butler #%s» и внесите её вручную, только если её там нет."
                            .formatted(order.id());
            case "SABY_WRITE_DISABLED" ->
                    "запись из Butler выключена. Внесите бронь в Presto вручную.";
            case "GUEST_DATA_REQUIRED" ->
                    "нет имени или телефона гостя. Внесите бронь в Presto вручную.";
            default ->
                    "бронь не записалась в Presto. Внесите её вручную.";
        };
        return "\n<b>Saby:</b> " + instruction + "\n";
    }

    private String hostessText(TableReservationOrder order) {
        return """
                <b>Бронь стола подтверждена командой</b>

                <b>Заказ:</b> #%s
                <b>Гость:</b> %s
                <b>Telegram:</b> chat %s / user %s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s
                <b>Телефон:</b> %s
                <b>Зона/пожелание:</b> %s

                <b>Исходный запрос гостя</b>
                <blockquote>%s</blockquote>

                Гостю отправлен красивый ордер. Если нужно добавить сабраж, предзаказ или комментарий для кухни, ответьте отдельным действием через соответствующий сценарий.
                """.formatted(
                order.id(),
                escape(blank(order.guestName(), "гость из Telegram")),
                escape(text(order.chatId())),
                escape(text(order.telegramUserId())),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize(),
                escape(blank(order.guestPhone(), "не указан")),
                escape(seatingPreference(order)),
                escape(blank(order.guestComment(), "нет"))
        );
    }

    private String guestConfirmedText(TableReservationOrder order) {
        return """
                <b>Бронь подтверждена</b>

                Ваш стол ждет вас.

                <b>Заказ:</b> #%s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s
                <b>Пожелание:</b> %s

                Если планы изменятся, просто напишите сюда, и я помогу все поправить.
                """.formatted(
                order.id(),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize(),
                escape(seatingPreference(order))
        );
    }

    private String guestRejectedText(TableReservationOrder order, List<VenueTable> alternatives) {
        return """
                <b>Пока не получилось подтвердить этот стол</b>

                По выбранному варианту команда хостес не смогла подтвердить бронь.

                <b>Заказ:</b> #%s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s
                <b>Пожелание:</b> %s

                %s
                """.formatted(
                order.id(),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize(),
                escape(seatingPreference(order)),
                escape(rejectionAlternativeText(alternatives))
        );
    }

    private String rejectionAlternativeText(List<VenueTable> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) {
            return "Я не бросаю вас на этом месте: напишите «подбери другой стол» или «перенести время», и я соберу новый вариант без повторной анкеты.";
        }
        VenueTable best = alternatives.getFirst();
        return "Проверил свободные варианты: первым я бы предложил " + humanTableDisplayName(best.displayName())
                + ". Напишите «да, другой стол» — и я передам команде новый вариант, либо выберите другое время.";
    }

    private String guestCancelledText(TableReservationOrder order) {
        return """
                <b>Бронь отменена</b>

                Я снял активную бронь и освободил стол.

                <b>Заказ:</b> #%s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s

                Если хотите выбрать другой стол или время, просто напишите новый запрос.
                """.formatted(
                order.id(),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order)
        );
    }

    private InlineKeyboardMarkup approvalKeyboard(Long orderId) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(
                        InlineKeyboardButton.builder()
                                .text("Да")
                                .callbackData("table_booking:confirm:" + orderId)
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("Нет")
                                .callbackData("table_booking:reject:" + orderId)
                                .build()
                )))
                .build();
    }

    private ReplyKeyboardMarkup guestMainMenuKeyboard() {
        return ReplyKeyboardMarkup.builder()
                .keyboard(List.of(
                        keyboardRow("Меню кухни", "Бар"),
                        keyboardRow("Коктейли", "Винная карта"),
                        keyboardRow("Видео-тур", "Бронь стола"),
                        keyboardRow("Связаться с командой", "Главное меню"),
                        keyboardRow("Сабраж", "Афиша"),
                        keyboardRow("Концепция AERIS"),
                        keyboardRow("Изменить / отменить", "Оставить отзыв"),
                        keyboardRow("Чаевые", "Донат"),
                        keyboardRow("Аукцион", "Мерч")
                ))
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .build();
    }

    private KeyboardRow keyboardRow(String... labels) {
        return new KeyboardRow(Arrays.stream(labels)
                .map(label -> KeyboardButton.builder().text(label).build())
                .toList());
    }

    private String hostessChatId(TableReservationOrder order) {
        return order.hostessChatId() == null || order.hostessChatId().isBlank()
                ? defaultHostessChatId
                : order.hostessChatId();
    }

    private String tableName(TableReservationOrder order) {
        if (order.tableCode() == null) {
            return "не выбран";
        }
        if (order.tableDisplayName() == null || order.tableDisplayName().isBlank()) {
            return "Стол " + order.tableCode();
        }
        return humanTableDisplayName(order.tableDisplayName()) + " (" + order.tableCode() + ")";
    }

    private String humanTableDisplayName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.startsWith("Table ")) {
            return "Стол " + value.substring("Table ".length());
        }
        if (value.startsWith("VIP ")) {
            return "Стол VIP " + value.substring("VIP ".length());
        }
        return value;
    }

    private String seatingPreference(TableReservationOrder order) {
        String preference = blank(order.seatingPreference(), "");
        if (!preference.isBlank()) {
            if (order.preferredZone() == null || order.preferredZone().isBlank()) {
                return preference;
            }
            return order.preferredZone() + " / " + preference;
        }
        return blank(order.preferredZone(), "нет");
    }

    private String hostessContext(TableReservationOrder order) {
        return "Проверьте стол, время, вместимость и пожелание гостя. Контакт: "
                + blank(order.guestPhone(), "нет телефона")
                + ". После подтверждения гостю автоматически уйдет order-card.";
    }

    private String recentMessages(TableReservationOrder order) {
        List<String> messages = guestContextRepository.recentMessages(order.chatId(), 5);
        if (messages.isEmpty()) {
            return "нет сохраненной истории";
        }
        StringBuilder builder = new StringBuilder();
        for (String message : messages) {
            builder.append("• ").append(escape(trimForCard(message))).append("\n");
        }
        return builder.toString().trim();
    }

    private String trimForCard(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 177) + "...";
    }

    private String humanStatus(TableReservationStatus status) {
        if (status == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
            return "ожидает решения хостес";
        }
        if (status == TableReservationStatus.CONFIRMED) {
            return "подтверждена";
        }
        if (status == TableReservationStatus.REJECTED) {
            return "отклонена";
        }
        return status == null ? "неизвестен" : status.name();
    }

    private String format(java.time.Instant instant) {
        return instant == null ? "не указано" : DATE_TIME.format(instant);
    }

    private String formatDate(java.time.Instant instant) {
        return instant == null ? "не указана" : DATE.format(instant);
    }

    private String formatTimeRange(TableReservationOrder order) {
        if (order.requestedStartAt() == null || order.requestedEndAt() == null) {
            return "не указано";
        }
        return TIME.format(order.requestedStartAt()) + " - " + TIME.format(order.requestedEndAt());
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
