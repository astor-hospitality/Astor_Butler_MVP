package museon_online.astor_butler.telegram.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.core.event.InboundEvent;
import museon_online.astor_butler.fsm.core.idempotency.IdempotencyGuard;
import museon_online.astor_butler.domain.billing.VisitReviewService;
import museon_online.astor_butler.domain.booking.HostessReservationApprovalService;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageGatewayService;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.telegram.exeption.TelegramExceptionHandler;
import museon_online.astor_butler.telegram.voice.TelegramVoiceReplyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.objects.Audio;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramRouter {
    private static final Pattern SAFE_PLAY_CALLBACK = Pattern.compile("^safe_play:(approve|reject):(-?\\d+)$");

    private final TelegramExceptionHandler exceptionHandler;
    private final IdempotencyGuard idempotencyGuard;
    private final MessageGatewayService messageGatewayService;
    private final TelegramChatViewService chatViewService;
    private final TelegramVoiceEnrichmentGuard voiceEnrichmentGuard;
    private final HostessReservationApprovalService hostessReservationApprovalService;
    private final TelegramMediaSender telegramMediaSender;
    private final VisitReviewService visitReviewService;
    private final TelegramVoiceReplyService voiceReplyService;

    @Value("${telegram.ui.preview-enabled:true}")
    private boolean previewEnabled;

    @Value("${telegram.ui.preview-version:2026-06-17-weekend-rc}")
    private String previewVersion;

    @Value("${telegram.ui.preview-avatar-path:classpath:telegram/aeris-butler-preview.png}")
    private Resource previewAvatar;

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;


    public void handle(Update update, AbsSender sender) {
        try {
            if (!acceptInboundEvent(update)) {
                return;
            }

            if (handleCallback(update, sender)) {
                return;
            }

            IncomingMessage incoming = toIncomingMessage(update);
            if (incoming == null) {
                log.debug("📭 [TG] Update ignored: cannot map to IncomingMessage");
                return;
            }
            incoming = voiceEnrichmentGuard.enrich(incoming, sender);

            log.info("📨 [TG] Incoming message from {}: {}", incoming.chatId(), incoming.text());
            if (hostessReservationApprovalService.handle(incoming)) {
                return;
            }
            String voiceCommandAnswer = voiceReplyService.handleCommand(incoming).orElse(null);
            if (voiceCommandAnswer != null) {
                execute(sender, SendMessage.builder().chatId(incoming.chatId().toString()).text(voiceCommandAnswer).build());
                return;
            }

            OutgoingMessage outgoing = messageGatewayService.handle(incoming);
            ensurePreview(incoming, sender, isStartCommand(incoming));
            telegramMediaSender.sendDocumentIfPresent(outgoing.chatId(), outgoing.metadata(), sender);
            telegramMediaSender.sendVideoIfPresent(outgoing.chatId(), outgoing.metadata(), sender);
            IncomingMessage request = incoming;
            if (!voiceReplyService.reply(request, outgoing, sender, message -> send(request, message, sender))) {
                send(incoming, outgoing, sender);
            }
            sendAdminAlert(outgoing, sender);

        } catch (Exception e) {
            log.error("💥 [TG] Exception while handling update: {}", e.getMessage(), e);
            exceptionHandler.handle(update, e, sender);
        }
    }

    private boolean handleCallback(Update update, AbsSender sender) {
        if (update == null || !update.hasCallbackQuery()) {
            return false;
        }

        CallbackQuery callbackQuery = update.getCallbackQuery();
        Long chatId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getChatId();

        TelegramVoiceReplyService.CallbackResult fullReply = voiceReplyService.handleCallback(callbackQuery.getData(), chatId, sender);
        if (fullReply.handled()) {
            execute(sender, AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .text(fullReply.answerText())
                    .showAlert(false)
                    .build());
            return true;
        }

        CallbackAnswer safePlayAnswer = handleSafePlayCallback(callbackQuery, chatId, sender);
        if (safePlayAnswer.handled()) {
            execute(sender, AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .text(safePlayAnswer.answerText())
                    .showAlert(false)
                    .build());
            return true;
        }

        VisitReviewService.CallbackResult review = visitReviewService.handleCallback(callbackQuery.getData(), chatId);
        if (review.handled()) {
            execute(sender, AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .text(review.answerText())
                    .showAlert(false)
                    .build());
            if (review.guestText() != null) {
                // The button stands for words the guest could have typed; the bot answers them the usual way.
                IncomingMessage asTyped = typedByGuest(callbackQuery, chatId, review.guestText());
                send(asTyped, messageGatewayService.handle(asTyped), sender);
            }
            return true;
        }

        HostessReservationApprovalService.CallbackResult result = hostessReservationApprovalService.handleCallback(
                callbackQuery.getData(),
                chatId
        );
        if (!result.handled()) {
            return false;
        }

        execute(sender, AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQuery.getId())
                .text(result.answerText())
                .showAlert(false)
                .build());
        return true;
    }

    private CallbackAnswer handleSafePlayCallback(CallbackQuery callbackQuery, Long callbackChatId, AbsSender sender) {
        if (callbackQuery == null || callbackQuery.getData() == null || !isAdminChat(callbackChatId)) {
            return CallbackAnswer.notHandled();
        }

        Matcher matcher = SAFE_PLAY_CALLBACK.matcher(callbackQuery.getData());
        if (!matcher.matches()) {
            return CallbackAnswer.notHandled();
        }

        String action = matcher.group(1);
        String guestChatId = matcher.group(2);
        String guestText = "approve".equals(action)
                ? """
                Команда AERIS приняла запрос на сабражный ритуал. Сейчас проверим бутылку, время, стол и доступность обученного сотрудника. Я вернусь с подтверждением деталей.
                """
                : """
                Сейчас не получится безопасно подтвердить сабражный ритуал. Можем предложить другой момент для подачи, помощь сомелье или спокойную альтернативу без ритуала.
                """;

        execute(sender, SendMessage.builder()
                .chatId(guestChatId)
                .text(guestText.strip())
                .build());

        return CallbackAnswer.handled("approve".equals(action)
                ? "Запрос на сабраж принят, гостю отправлено сообщение"
                : "Запрос на сабраж отклонен, гостю отправлено сообщение");
    }
    private boolean acceptInboundEvent(Update update) {
        try {
            InboundEvent inboundEvent = InboundEvent.from(update);

            if (inboundEvent == null) {
                log.debug("📭 [PIPELINE] Update ignored (cannot be mapped to InboundEvent)");
                return true;
            }

            boolean accepted = idempotencyGuard.accept(inboundEvent);
            if (!accepted) {
                log.info(
                        "🔁 [PIPELINE] Duplicate event ignored (eventId={}, chatId={})",
                        inboundEvent.getEventId(),
                        inboundEvent.getChatId()
                );
                return false;
            }

            log.info(
                    "➡️ [PIPELINE] InboundEvent accepted by idempotency guard → forwarding to MessageGateway (eventId={}, type={}, chatId={})",
                    inboundEvent.getEventId(),
                    inboundEvent.getType(),
                    inboundEvent.getChatId()
            );

            return true;

        } catch (Exception e) {
            log.error("💥 [PIPELINE] Error while processing inbound event", e);
            return true;
        }
    }

    private IncomingMessage toIncomingMessage(Update update) {
        if (update == null || update.getMessage() == null) {
            return null;
        }

        Message message = update.getMessage();
        Long chatId = message.getChatId();
        User user = message.getFrom();
        Contact contact = message.getContact();
        Map<String, Object> payload = mediaPayload(message);
        payload = replyPayload(message, payload);

        return IncomingMessage.telegram(
                chatId,
                user == null ? null : user.getId(),
                message.getMessageId(),
                update.getUpdateId(),
                message.hasText() ? message.getText() : "",
                contact == null ? null : contact.getPhoneNumber(),
                user == null ? null : user.getFirstName(),
                user == null ? null : user.getLastName(),
                user == null ? null : user.getUserName(),
                user == null ? null : user.getLanguageCode(),
                user == null ? null : user.getIsBot(),
                update.getUpdateId() == null ? UUID.randomUUID().toString() : update.getUpdateId().toString(),
                payload
        );
    }

    private IncomingMessage typedByGuest(CallbackQuery callbackQuery, Long chatId, String text) {
        User user = callbackQuery.getFrom();
        return IncomingMessage.telegram(
                chatId,
                user == null ? null : user.getId(),
                null,
                null,
                text,
                null,
                user == null ? null : user.getFirstName(),
                user == null ? null : user.getLastName(),
                user == null ? null : user.getUserName(),
                user == null ? null : user.getLanguageCode(),
                user == null ? null : user.getIsBot(),
                "callback:" + callbackQuery.getId()
        );
    }

    private void send(IncomingMessage incoming, OutgoingMessage outgoing, AbsSender sender) {
        if (outgoing == null || outgoing.chatId() == null || outgoing.text() == null || outgoing.text().isBlank()) {
            return;
        }

        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                .chatId(outgoing.chatId().toString())
                .text(outgoing.text());

        Integer replyToMessageId = intMetadata(outgoing, "replyToMessageId");
        if (replyToMessageId != null) {
            builder.replyToMessageId(replyToMessageId).allowSendingWithoutReply(true);
        }

        if (outgoing.html()) {
            builder.parseMode("HTML");
        }
        ReplyKeyboardMarkup customKeyboard = customReplyKeyboard(outgoing);
        if (outgoing.requestContact() && outgoing.chatId() != null && outgoing.chatId() > 0) {
            builder.replyMarkup(contactKeyboard());
        } else if (customKeyboard != null) {
            builder.replyMarkup(customKeyboard);
        } else if (shouldShowGuestMainMenu(outgoing)) {
            builder.replyMarkup(guestMainMenuKeyboard());
        } else if (outgoing.removeKeyboard()) {
            builder.replyMarkup(ReplyKeyboardRemove.builder().removeKeyboard(true).build());
        }

        Message sent = execute(sender, builder.build());
        if (sent != null) {
            log.info(
                    "📤 [TG] Outgoing message sent to {}: messageId={}, actions={}",
                    outgoing.chatId(),
                    sent.getMessageId(),
                    outgoing.actions()
            );
        }
    }

    private void sendAdminAlert(OutgoingMessage outgoing, AbsSender sender) {
        if (outgoing == null
                || outgoing.adminAlert() == null
                || !outgoing.adminAlert().required()
                || outgoing.adminAlert().chatId() == null
                || outgoing.adminAlert().chatId().isBlank()) {
            return;
        }

        execute(sender, SendMessage.builder()
                .chatId(outgoing.adminAlert().chatId())
                .text(outgoing.adminAlert().text())
                .parseMode("HTML")
                .replyMarkup(adminAlertKeyboard(outgoing.adminAlert()))
                .build());
    }

    private InlineKeyboardMarkup adminAlertKeyboard(museon_online.astor_butler.service.message.AdminAlert alert) {
        if (alert == null || alert.buttons() == null || alert.buttons().isEmpty()) {
            return null;
        }
        return InlineKeyboardMarkup.builder()
                .keyboard(alert.buttons().stream()
                        .map(row -> row.stream()
                                .map(button -> InlineKeyboardButton.builder()
                                        .text(button.text())
                                        .callbackData(button.callbackData())
                                        .build())
                                .toList())
                        .toList())
                .build();
    }

    private ReplyKeyboardMarkup contactKeyboard() {
        KeyboardButton shareContact = KeyboardButton.builder()
                .text("Согласиться и поделиться контактом")
                .requestContact(true)
                .build();

        return ReplyKeyboardMarkup.builder()
                .keyboard(List.of(new KeyboardRow(List.of(shareContact))))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
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

    private ReplyKeyboardMarkup customReplyKeyboard(OutgoingMessage outgoing) {
        if (outgoing == null || outgoing.metadata() == null) {
            return null;
        }
        Object rawRows = outgoing.metadata().get("replyKeyboardRows");
        if (!(rawRows instanceof List<?> rows) || rows.isEmpty()) {
            return null;
        }
        List<KeyboardRow> keyboardRows = new java.util.ArrayList<>();
        for (Object rowObject : rows) {
            if (!(rowObject instanceof List<?> rowValues)) {
                continue;
            }
            List<String> labels = rowValues.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
            if (!labels.isEmpty()) {
                keyboardRows.add(keyboardRow(labels.toArray(String[]::new)));
            }
        }
        if (keyboardRows.isEmpty()) {
            return null;
        }
        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboardRows)
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .build();
    }

    private KeyboardRow keyboardRow(String... labels) {
        return new KeyboardRow(Arrays.stream(labels)
                .map(label -> KeyboardButton.builder().text(label).build())
                .toList());
    }

    private boolean shouldShowGuestMainMenu(OutgoingMessage outgoing) {
        return outgoing != null
                && outgoing.chatId() != null
                && outgoing.chatId() > 0
                && BotState.READY_FOR_DIALOG.name().equals(outgoing.nextState())
                && !outgoing.requestContact();
    }

    private void ensurePreview(IncomingMessage incoming, AbsSender sender, boolean force) {
        if (!previewEnabled
                || incoming == null
                || incoming.chatId() == null
                || incoming.telegramUserId() == null
                || incoming.chatId() < 0
                || (!force && chatViewService.findPreviewMessageId(incoming.telegramUserId(), previewVersion) != null)) {
            return;
        }

        String filename = previewAvatar.getFilename() == null ? "aeris-butler-preview.png" : previewAvatar.getFilename();
        try (InputStream inputStream = previewAvatar.getInputStream()) {
            Message preview = sender.execute(SendPhoto.builder()
                    .chatId(incoming.chatId().toString())
                    .photo(new InputFile(inputStream, filename))
                    .caption(previewText())
                    .parseMode("HTML")
                    .build());

            if (preview != null) {
                chatViewService.savePreviewMessageId(incoming.telegramUserId(), preview.getMessageId(), previewVersion);
                pinPreview(incoming, preview, sender);
            }
        } catch (Exception e) {
            log.warn("Telegram preview was not sent: {}", e.getMessage());
        }
    }

    private void pinPreview(IncomingMessage incoming, Message preview, AbsSender sender) {
        if (incoming == null || preview == null || incoming.chatId() == null || preview.getMessageId() == null) {
            return;
        }
        try {
            execute(sender, PinChatMessage.builder()
                    .chatId(incoming.chatId().toString())
                    .messageId(preview.getMessageId())
                    .disableNotification(true)
                    .build());
        } catch (Exception e) {
            log.debug("Telegram preview pin skipped: {}", e.getMessage());
        }
    }

    private boolean isStartCommand(IncomingMessage incoming) {
        return incoming != null && incoming.text() != null && "/start".equalsIgnoreCase(incoming.text().trim());
    }

    private boolean isAdminChat(Long chatId) {
        return chatId != null && adminChatId != null && !adminChatId.isBlank() && adminChatId.equals(chatId.toString());
    }

    private Map<String, Object> mediaPayload(Message message) {
        if (message == null) {
            return Map.of();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (message.hasVoice()) {
            Voice voice = message.getVoice();
            payload.put("mediaKind", "VOICE");
            payload.put("telegramFileId", voice.getFileId());
            payload.put("durationSeconds", voice.getDuration());
            payload.put("fileSizeBytes", voice.getFileSize());
            payload.put("mimeType", voice.getMimeType());
        } else if (message.hasAudio()) {
            Audio audio = message.getAudio();
            payload.put("mediaKind", "AUDIO");
            payload.put("telegramFileId", audio.getFileId());
            payload.put("durationSeconds", audio.getDuration());
            payload.put("fileSizeBytes", audio.getFileSize());
            payload.put("mimeType", audio.getMimeType());
            payload.put("fileName", audio.getFileName());
        }
        return payload;
    }

    private Map<String, Object> replyPayload(Message message, Map<String, Object> existingPayload) {
        if (message == null || message.getReplyToMessage() == null) {
            return existingPayload;
        }
        Map<String, Object> payload = new LinkedHashMap<>(existingPayload == null ? Map.of() : existingPayload);
        Message reply = message.getReplyToMessage();
        payload.put("replyToMessageId", reply.getMessageId());
        if (reply.hasText()) {
            payload.put("replyToText", reply.getText());
        }
        if (reply.getFrom() != null) {
            payload.put("replyToUserId", reply.getFrom().getId());
            payload.put("replyToUsername", reply.getFrom().getUserName());
            payload.put("replyToBot", reply.getFrom().getIsBot());
        }
        return payload;
    }

    private Integer intMetadata(OutgoingMessage outgoing, String key) {
        if (outgoing == null || outgoing.metadata() == null) {
            return null;
        }
        Object value = outgoing.metadata().get(key);
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String previewText() {
        return """
                <b><a href="https://aeris.bar/">AERIS</a> · Astor Butler</b>

                Я на связи: помогу с бронью, меню, винной картой, афишей, видео-туром, сабражем и быстрым контактом с командой.

                Можно выбрать кнопку ниже, написать своими словами или отправить голосовое:
                <i>хочу стол на завтра в 20:00</i>
                <i>покажи меню и вино</i>
                <i>сабраж</i>
                <i>расскажи про AERIS</i>

                <a href="https://michaelwelly.github.io/Astor_Butler_MVP/docs/guest-guide.html">Короткая инструкция гостя</a>
                """;
    }

    private <T extends Serializable> T execute(AbsSender sender, BotApiMethod<T> method) {
        try {
            return sender.execute(method);
        } catch (TelegramApiRequestException e) {
            Long migratedChatId = migratedChatId(e);
            if (migratedChatId != null && method instanceof SendMessage sendMessage) {
                log.warn(
                        "Telegram chat migrated to supergroup. Update TELEGRAM_ADMIN_CHAT_ID/TELEGRAM_ANALYTICS_CHAT_ID to {}. Retrying once.",
                        migratedChatId
                );
                try {
                    sendMessage.setChatId(migratedChatId);
                    @SuppressWarnings("unchecked")
                    T result = (T) sender.execute(sendMessage);
                    return result;
                } catch (Exception retryException) {
                    log.error("Telegram API retry after chat migration failed: {}", method.getMethod(), retryException);
                    return null;
                }
            }
            log.error("Telegram API call failed: {}", method.getMethod(), e);
        } catch (Exception e) {
            log.error("Telegram API call failed: {}", method.getMethod(), e);
        }
        return null;
    }

    private Long migratedChatId(TelegramApiRequestException e) {
        ResponseParameters parameters = e.getParameters();
        if (parameters != null && parameters.getMigrateToChatId() != null) {
            return parameters.getMigrateToChatId();
        }

        String apiResponse = e.getApiResponse();
        if (apiResponse == null || apiResponse.isBlank()) {
            return null;
        }

        int marker = apiResponse.indexOf("migrate_to_chat_id");
        if (marker < 0) {
            return null;
        }

        String suffix = apiResponse.substring(marker).replaceAll("[^0-9-]", " ").trim();
        if (suffix.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(suffix.split("\\s+")[0]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record CallbackAnswer(boolean handled, String answerText) {
        static CallbackAnswer handled(String answerText) {
            return new CallbackAnswer(true, answerText);
        }

        static CallbackAnswer notHandled() {
            return new CallbackAnswer(false, "");
        }
    }
}
