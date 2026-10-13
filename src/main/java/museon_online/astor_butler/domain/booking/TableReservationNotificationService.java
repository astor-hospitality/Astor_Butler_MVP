package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.billing.HostessBillRequestService;
import museon_online.astor_butler.domain.billing.ReviewMoment;
import museon_online.astor_butler.domain.booking.BookingAutoConfirmPolicy.Decision;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import museon_online.astor_butler.domain.entry.GuestEntryService;
import museon_online.astor_butler.domain.lunch.LunchPreorderMark;
import museon_online.astor_butler.domain.telegram.TelegramGuestContextRepository;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
    private final BookingAutoConfirmPolicy autoConfirmPolicy;
    private final BookingHostessStateStorage hostessStateStorage;

    /** The usefulness filter for bot-first messages (spec 2026-10-13): optional, allows everything while off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.UsefulnessPolicy usefulnessPolicy;

    public void setUsefulnessPolicy(museon_online.astor_butler.domain.journey.UsefulnessPolicy usefulnessPolicy) {
        this.usefulnessPolicy = usefulnessPolicy;
    }

    /** The guest journey ledger (spec 2026-10-13): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents;

    public void setJourneyEvents(museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents) {
        this.journeyEvents = journeyEvents;
    }

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled;

    @Value("${telegram.booking.manager-chat-id:876857557}")
    private String defaultManagerChatId;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String defaultHostessChatId;

    /** {@code astor.review.moment}: with {@code on-bill} the hostess card carries «Счёт», which asks the guest how it went. */
    @Value("${astor.review.moment:after-close}")
    private String reviewMoment = "after-close";

    /** {@code astor.booking.staff-edit.enabled} (#150): with it on, the hostess card carries «✏️ Изменить». */
    @Value("${astor.booking.staff-edit.enabled:false}")
    private boolean staffEditEnabled = false;

    /** With the hold expiry on (#87) the card is remembered too, so «⏱ Истекло» can close it later. */
    @Value("${astor.booking.hold-expiry.enabled:false}")
    private boolean holdExpiryEnabled;

    /** {@code astor.booking.seated.enabled} (#151): with it on, the confirmed card carries «🪑 Гость сел». */
    @Value("${astor.booking.seated.enabled:false}")
    private boolean seatedEnabled = false;

    /** The system chat for the «гость сел» trace line (#151): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TelegramSystemNotifier systemNotifier;

    /** Who brought the guest in (#128): one line on the card while the feature is on. Optional so the constructor stays. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GuestEntryService guestEntryService;

    /** The kitchen's «готовить ко времени» (#164): one bold line on a lunch card while the mark is on. Optional so the constructor stays. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LunchPreorderMark preorderMark;

    /**
     * @param externalSync outcome of writing the order to the restaurant's own system; the card
     *                     tells the hostess what is already there and what she has to enter herself
     */
    public void notifyHostessApprovalRequest(TableReservationOrder order, ExternalReservationResult externalSync) {
        Decision decision = autoConfirmDecision(order);
        String chatId = hostessChatId(order);
        Message sent = send(
                chatId,
                hostessApprovalRequestText(order) + externalSyncLine(order, externalSync) + handoffLine(order) + preorderLine(order) + modeLine(order, decision),
                approvalKeyboard(order.id()),
                "hostess-approval-request"
        );
        if (sent != null && ((decision != null && decision != Decision.OFF) || holdExpiryEnabled)) {
            rememberHostessCard(order, chatId, sent);
        }
    }

    /** The hold ran out (#87): the card loses its buttons and a note under it says «⏱ Истекло». */
    public void notifyHostessHoldExpired(TableReservationOrder order, int afterMinutes) {
        Optional<BookingHostessStateStorage.CardRef> card = hostessCard(order);
        card.ifPresent(ref -> replaceButtons(ref, null));
        send(hostessChatId(order), hostessHoldExpiredText(order, afterMinutes), null,
                card.map(BookingHostessStateStorage.CardRef::messageId).orElse(null), "hostess-hold-expired");
    }

    /** The same card the manager gets from the timer (#148), minus the phone: «Да» takes the table again, «Нет» declines. */
    public void notifyManagerHoldExpired(TableReservationOrder order, int afterMinutes) {
        send(managerChatId(order), managerHoldExpiredText(order, afterMinutes), managerDecisionKeyboard(order.id()),
                "manager-hold-expired");
    }

    /** «Команда не успела подтвердить бронь за N минут»: the guest may wait for the manager or pick another time. */
    public void notifyGuestHoldExpired(TableReservationOrder order, int afterMinutes) {
        if (order.chatId() == null) {
            return;
        }
        send(order.chatId().toString(), guestHoldExpiredText(order, afterMinutes), guestMainMenuKeyboard(), "guest-hold-expired");
    }

    /** The card nobody answered confirmed itself: its buttons go, a note under it says so. */
    public void notifyHostessAutoConfirmed(TableReservationOrder order, int afterMinutes) {
        Optional<BookingHostessStateStorage.CardRef> card = hostessCard(order);
        // «Да» / «Нет» go; «Счёт» stays when the review is asked at the bill, as under a card the hostess confirmed.
        card.ifPresent(ref -> replaceButtons(ref, billKeyboard(order.id())));
        send(hostessChatId(order), hostessAutoConfirmedText(order, afterMinutes), null,
                card.map(BookingHostessStateStorage.CardRef::messageId).orElse(null), "hostess-auto-confirmed");
    }

    /** The card nobody answered went to the manager: a note under it says so; its buttons stay, whoever answers first decides. */
    public void notifyHostessEscalated(TableReservationOrder order, Decision decision, int afterMinutes) {
        Optional<BookingHostessStateStorage.CardRef> card = hostessCard(order);
        send(hostessChatId(order), hostessEscalatedText(order, decision, afterMinutes), null,
                card.map(BookingHostessStateStorage.CardRef::messageId).orElse(null), "hostess-escalated");
    }

    public void notifyManagerEscalation(TableReservationOrder order, Decision decision, int afterMinutes) {
        send(managerChatId(order), managerEscalationText(order, decision, afterMinutes), managerDecisionKeyboard(order.id()),
                "manager-escalation");
    }

    public void notifyGuestEscalatedToManager(TableReservationOrder order) {
        if (order.chatId() == null) {
            return;
        }
        send(order.chatId().toString(), guestEscalatedText(order), guestMainMenuKeyboard(), "guest-escalated");
    }

    /** A plain answer to a staff command in the chat it came from. */
    public void sendStaffNotice(String chatId, String text) {
        send(chatId, text, null, "staff-notice");
    }

    public void notifyHostessExternalCancelFailed(TableReservationOrder order) {
        send(hostessChatId(order), """
                <b>Saby:</b> бронь #%s не снялась в Presto автоматически. Проверьте и отмените её там вручную.
                """.formatted(order.id()), null, "hostess-external-cancel-failed");
    }

    public void notifyHostessConfirmed(TableReservationOrder order) {
        String chatId = hostessChatId(order);
        send(chatId, hostessText(order), billKeyboard(order.id()), "hostess");
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

    /**
     * Presto says the guest is at the table (#151): one short line in the hostess chat, under the remembered card
     * when there is one. The button path edits the pressed card instead, so nothing is said twice.
     */
    public void notifyHostessSeatedByVenue(TableReservationOrder order) {
        Optional<BookingHostessStateStorage.CardRef> card = hostessCard(order);
        send(hostessChatId(order), hostessSeatedByVenueText(order), null,
                card.map(BookingHostessStateStorage.CardRef::messageId).orElse(null), "hostess-seated-venue");
    }

    /** «🪑 Бронь #N: гость сел в 14:32 …» in the system chat, next to the dialogue traces; silent without that chat. */
    public void traceSeated(TableReservationOrder order, String who) {
        if (systemNotifier == null) {
            return;
        }
        try {
            systemNotifier.sendSystemNotice(seatedTraceText(order, who));
        } catch (RuntimeException e) {
            log.debug("Seated trace was not sent to the system chat: orderId={}, reason={}", order.id(), e.toString());
        }
    }

    String hostessSeatedByVenueText(TableReservationOrder order) {
        return """
                🪑 <b>Гость сел</b> %s — бронь #%s, %s, гостей: %s. Источник: Presto.
                """.formatted(formatTime(order.seatedAt()), order.id(), escape(tableName(order)), order.partySize());
    }

    String seatedTraceText(TableReservationOrder order, String who) {
        return "🪑 Бронь #" + order.id() + ": гость сел в " + formatTime(order.seatedAt()) + " — " + tableName(order)
                + ", гостей: " + order.partySize() + ". Отметил(а): " + blank(who, "неизвестно");
    }

    /** «🪑 Гость сел 14:32», the line the button adds to the pressed card (#151). */
    static String seatedLine(TableReservationOrder order, String who) {
        String line = "🪑 Гость сел " + formatTime(order.seatedAt());
        return who == null || who.isBlank() ? line : line + " — " + who;
    }

    /**
     * The guest wrote «счёт» to the bot (#153 A2): the staff chat gets a card in the usual style, without the phone,
     * so the waiter brings the bill. Returns whether the card left Butler.
     */
    /** A guest's table request (spec 2026-10-13 §3.3) as a card to the staff chat. */
    public void notifyStaffGuestRequest(TableReservationOrder order, museon_online.astor_butler.domain.journey.talk.TableRequest request,
                                        String guestText, String waiterName) {
        String text = """
                🙋 <b>%s просит: %s</b>

                <b>Гость:</b> %s
                <b>Написал:</b> «%s»
                <b>Официант:</b> %s
                """.formatted(escape(tableName(order)), escape(request.what()), escape(blank(order.guestName(), "гость из Telegram")),
                escape(guestText == null ? "" : guestText), escape(blank(waiterName, "не назначен")));
        send(hostessChatId(order), text, null, "staff-guest-request");
    }

    /** A guest's question the bot has no confirmed answer to, for the team. */
    public void notifyStaffGuestQuestion(long guestChatId, String question) {
        String text = """
                ❓ <b>Гость спрашивает, а у бота нет ответа</b>

                «%s»

                Ответьте гостю или добавьте факт в справку бота (aeris-venue.json).
                """.formatted(escape(question == null ? "" : question));
        send(defaultHostessChatId, text, null, "staff-guest-question");
    }

    /** A plain useful text to the guest of the reservation. */
    public void notifyGuestText(TableReservationOrder order, String text, String target) {
        if (order == null || order.chatId() == null || text == null || text.isBlank()) {
            return;
        }
        send(order.chatId().toString(), text, null, target);
    }

    public boolean notifyHostessGuestAskedForBill(TableReservationOrder order) {
        return send(hostessChatId(order), guestAskedForBillText(order), null, "hostess-guest-bill-request") != null;
    }

    String guestAskedForBillText(TableReservationOrder order) {
        String who = order.tableCode() == null ? "Гость" : tableName(order);
        return """
                💳 <b>%s просит счёт</b> (гость написал в боте)

                <b>Заказ:</b> #%s
                <b>Гость:</b> %s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s

                Принесите счёт к столу. Гостю ответил, что счёт сейчас будет.
                """.formatted(
                escape(who),
                order.id(),
                escape(blank(order.guestName(), "гость из Telegram")),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize() == null ? "не указано" : order.partySize()
        );
    }

    public void notifyGuestConfirmed(TableReservationOrder order) {
        if (journeyEvents != null) {
            journeyEvents.onReservation(order, museon_online.astor_butler.domain.journey.VisitEventKind.CONFIRMED, museon_online.astor_butler.domain.journey.JourneyEvents.BOT);
        }
        if (order.chatId() == null) {
            return;
        }
        if (usefulnessPolicy != null && !usefulnessPolicy.permits(order.id(), order.chatId(), museon_online.astor_butler.domain.journey.ProactiveMessage.CONFIRMED)) {
            pendingIntentService.deliverAfterConfirmation(order);
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
        if (journeyEvents != null) {
            journeyEvents.onReservation(order, museon_online.astor_butler.domain.journey.VisitEventKind.CANCELLED, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST);
        }
        if (order.chatId() == null) {
            return;
        }
        send(order.chatId().toString(), guestCancelledText(order), guestMainMenuKeyboard(), "guest-cancelled");
    }

    /** The hostess moved the booking to another time (#150): the guest hears the new time and whether the table stayed. */
    public void notifyGuestMovedByStaff(TableReservationOrder changed, TableReservationOrder before) {
        if (journeyEvents != null) {
            journeyEvents.onReservation(changed, museon_online.astor_butler.domain.journey.VisitEventKind.MOVED, museon_online.astor_butler.domain.journey.JourneyEvents.STAFF);
        }
        if (changed.chatId() == null) {
            return;
        }
        send(changed.chatId().toString(), guestMovedByStaffText(changed, before), guestMainMenuKeyboard(), "guest-moved-by-staff");
    }

    String guestMovedByStaffText(TableReservationOrder changed, TableReservationOrder before) {
        boolean sameTable = before == null || before.tableCode() == null
                || before.tableCode().equalsIgnoreCase(changed.tableCode());
        String table = sameTable
                ? "стол тот же — " + escape(tableName(changed))
                : "стол теперь " + escape(tableName(changed));
        return """
                <b>Бронь #%s перенесена</b>

                Хостес перенесла вашу бронь на %s, %s; %s, гостей: %s.

                Если это время не подходит, просто напишите сюда или нажмите «Изменить / отменить» в меню.
                """.formatted(
                changed.id(),
                formatDate(changed.requestedStartAt()),
                formatTimeRange(changed),
                table,
                changed.partySize()
        );
    }

    private Message send(String chatId, String text, ReplyKeyboard keyboard, String target) {
        return send(chatId, text, keyboard, null, target);
    }

    /** Returns the sent message, or null when nothing was sent. */
    private Message send(String chatId, String text, ReplyKeyboard keyboard, Integer replyToMessageId, String target) {
        if (!telegramEnabled || !notificationsEnabled || chatId == null || chatId.isBlank()) {
            log.debug("Table reservation Telegram notification skipped: target={}, botEnabled={}, notificationsEnabled={}, chatConfigured={}",
                    target,
                    telegramEnabled,
                    notificationsEnabled,
                    chatId != null && !chatId.isBlank());
            return null;
        }

        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                log.debug("Table reservation Telegram notification skipped: bot bean is not available");
                return null;
            }
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard);
            if (replyToMessageId != null) {
                builder.replyToMessageId(replyToMessageId).allowSendingWithoutReply(true);
            }
            return telegramBot.execute(builder.build());
        } catch (Exception e) {
            log.warn("Table reservation Telegram notification failed: target={}, orderId={}, reason={}",
                    target,
                    text.contains("#") ? "included" : "unknown",
                    e.getMessage());
            return null;
        }
    }

    /** Swaps the buttons of a decided card for the given ones (none when null); an old or deleted card is left as it is. */
    private void replaceButtons(BookingHostessStateStorage.CardRef card, InlineKeyboardMarkup keyboard) {
        if (!telegramEnabled || !notificationsEnabled || card == null) {
            return;
        }
        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                return;
            }
            telegramBot.execute(EditMessageReplyMarkup.builder()
                    .chatId(card.chatId())
                    .messageId(card.messageId())
                    .replyMarkup(keyboard)
                    .build());
        } catch (Exception e) {
            log.debug("Hostess card buttons were not replaced: chatId={}, messageId={}, reason={}",
                    card.chatId(), card.messageId(), e.getMessage());
        }
    }

    /** The manager only decides: «Счёт» belongs to the hostess chat, where its callback is handled. */
    InlineKeyboardMarkup managerDecisionKeyboard(Long orderId) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(approvalKeyboard(orderId).getKeyboard().getFirst()))
                .build();
    }

    /** Null when the policy cannot be asked right now: the card then reads exactly as before. */
    private Decision autoConfirmDecision(TableReservationOrder order) {
        if (autoConfirmPolicy == null) {
            return null;
        }
        try {
            return autoConfirmPolicy.decide(order);
        } catch (RuntimeException e) {
            log.warn("Auto-confirm policy is not available, the hostess card goes out without a mode line: orderId={}, reason={}",
                    order.id(), e.toString());
            return null;
        }
    }

    private void rememberHostessCard(TableReservationOrder order, String chatId, Message sent) {
        if (hostessStateStorage == null) {
            return;
        }
        try {
            hostessStateStorage.rememberHostessCard(order.id(), chatId, sent.getMessageId());
        } catch (RuntimeException e) {
            log.warn("Hostess card reference was not stored: orderId={}, reason={}", order.id(), e.toString());
        }
    }

    private Optional<BookingHostessStateStorage.CardRef> hostessCard(TableReservationOrder order) {
        if (hostessStateStorage == null) {
            return Optional.empty();
        }
        try {
            return hostessStateStorage.hostessCard(order.id());
        } catch (RuntimeException e) {
            log.debug("Hostess card reference was not read: orderId={}, reason={}", order.id(), e.toString());
            return Optional.empty();
        }
    }

    /** What the timer will do with this card; empty while the feature is off, so the card reads exactly as before. */
    String modeLine(TableReservationOrder order, Decision decision) {
        if (decision == null) {
            return "";
        }
        int minutes = autoConfirmPolicy == null ? 0 : autoConfirmPolicy.timeoutMinutes();
        return switch (decision) {
            case OFF, VENUE_SYSTEM -> "";
            case AUTO -> ("\n<b>Автоподтверждение</b> — без ответа через %d мин заявка подтвердится сама и гость получит ордер. "
                    + "«Нет» до этого — отказ, как обычно.\n").formatted(minutes);
            case MANUAL_MODE -> ("\n<b>Ручной режим («перегружены»)</b> — автоподтверждения нет, без ответа через %d мин заявка уйдёт менеджеру. "
                    + "Вернуть автоподтверждение: /manual off\n").formatted(minutes);
            case LARGE_PARTY -> ("\n<b>Требует решения: %s гостей</b> — автоподтверждения нет, без ответа через %d мин заявка уйдёт менеджеру.\n")
                    .formatted(order.partySize(), minutes);
            case EXCLUDED_ZONE -> ("\n<b>Требует решения: зона %s</b> — автоподтверждения нет, без ответа через %d мин заявка уйдёт менеджеру.\n")
                    .formatted(escape(zoneLabel(order)), minutes);
        };
    }

    String hostessAutoConfirmedText(TableReservationOrder order, int afterMinutes) {
        return """
                <b>Подтверждено автоматически через %d мин</b>

                Бронь #%s: ответа не было, гостю отправлен ордер — %s, %s, %s, гостей: %s.
                """.formatted(
                afterMinutes,
                order.id(),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize()
        );
    }

    String hostessEscalatedText(TableReservationOrder order, Decision decision, int afterMinutes) {
        return """
                <b>%d минут без ответа</b> — заявка #%s передана менеджеру (%s).
                Кнопки на карточке работают: кто ответит первым, тот и решит.
                """.formatted(afterMinutes, order.id(), escape(escalationReason(order, decision)));
    }

    String managerEscalationText(TableReservationOrder order, Decision decision, int afterMinutes) {
        return """
                <b>⏱ %d минут без ответа, %s</b>
                Хостес не ответила на заявку — решение за вами: «Да» подтвердит бронь гостю, «Нет» — вежливый отказ с альтернативой.

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
                """.formatted(
                afterMinutes,
                escape(escalationReason(order, decision)),
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

    String guestEscalatedText(TableReservationOrder order) {
        return """
                <b>Заявка у менеджера</b>

                Хостес сейчас занята, поэтому заявку #%s смотрит менеджер — ответим в ближайшее время.

                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s

                Если планы изменятся, просто напишите сюда, и я помогу все поправить.
                """.formatted(
                order.id(),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize()
        );
    }

    String hostessHoldExpiredText(TableReservationOrder order, int afterMinutes) {
        return """
                <b>⏱ Истекло</b> — %d мин без ответа.
                Заявка #%s: стол освобождён, гостю предложено выбрать другое время, карточка передана менеджеру.
                """.formatted(afterMinutes, order.id());
    }

    /** No phone and no chat ids here: the manager decides by the request, the bot talks to the guest. */
    String managerHoldExpiredText(TableReservationOrder order, int afterMinutes) {
        return """
                <b>⏱ %d мин без ответа — удержание истекло</b>
                Хостес не ответила на заявку #%s: стол освобождён, гостю предложено выбрать другое время. Решение за вами: «Да» снова займёт стол, если он ещё свободен, и подтвердит бронь гостю; «Нет» — вежливый отказ с альтернативой.

                <b>Заказ:</b> #%s
                <b>Гость:</b> %s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s
                <b>Зона/пожелание:</b> %s

                <b>Исходный запрос гостя</b>
                <blockquote>%s</blockquote>
                """.formatted(
                afterMinutes,
                order.id(),
                order.id(),
                escape(blank(order.guestName(), "гость из Telegram")),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize(),
                escape(seatingPreference(order)),
                escape(blank(order.guestComment(), "нет"))
        );
    }

    String guestHoldExpiredText(TableReservationOrder order, int afterMinutes) {
        return """
                <b>Команда не успела подтвердить бронь за %d минут.</b>

                Передаю менеджеру смены — вам ответят, или выберите другое время.

                <b>Заказ:</b> #%s
                <b>Стол:</b> %s
                <b>Дата:</b> %s
                <b>Время:</b> %s
                <b>Гостей:</b> %s

                Чтобы выбрать другое время, нажмите «Бронь стола» или просто напишите, когда вам удобно.
                """.formatted(
                afterMinutes,
                order.id(),
                escape(tableName(order)),
                formatDate(order.requestedStartAt()),
                formatTimeRange(order),
                order.partySize()
        );
    }

    void setHoldExpiryEnabled(boolean holdExpiryEnabled) {
        this.holdExpiryEnabled = holdExpiryEnabled;
    }

    private String escalationReason(TableReservationOrder order, Decision decision) {
        if (decision == null) {
            return "без ответа";
        }
        return switch (decision) {
            case LARGE_PARTY -> order.partySize() + " гостей";
            case EXCLUDED_ZONE -> "зона " + zoneLabel(order);
            default -> "ручной режим";
        };
    }

    private String zoneLabel(TableReservationOrder order) {
        return blank(order.preferredZone(), tableName(order));
    }

    private String managerChatId(TableReservationOrder order) {
        if (defaultManagerChatId != null && !defaultManagerChatId.isBlank()) {
            return defaultManagerChatId;
        }
        return order.managerTelegramId() == null ? null : order.managerTelegramId().toString();
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

    /** «Привёл(а): Анна» (#128) when the guest came in through a staff link within the session window; empty otherwise. */
    String handoffLine(TableReservationOrder order) {
        if (guestEntryService == null || order == null || order.chatId() == null) {
            return "";
        }
        return guestEntryService.responsibleFor(order.chatId())
                .map(name -> "\n<b>Привёл(а):</b> " + escape(name) + "\n")
                .orElse("");
    }

    void setGuestEntryService(GuestEntryService guestEntryService) {
        this.guestEntryService = guestEntryService;
    }

    /**
     * «⏱ Готовить ко времени 13:00» (#164) on the card of a business lunch while the pre-order mark is on — the same time the
     * kitchen reads at the start of the Saby comment, so the hostess can write it on the ticket herself when the dishes did not
     * reach Presto. Empty for any other reservation and while the mark is off, so the card stays as it was.
     */
    String preorderLine(TableReservationOrder order) {
        if (preorderMark == null) {
            return "";
        }
        return preorderMark.cardLine(order)
                .map(line -> "\n<b>" + escape(line) + "</b>\n")
                .orElse("");
    }

    void setPreorderMark(LunchPreorderMark preorderMark) {
        this.preorderMark = preorderMark;
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

    InlineKeyboardMarkup approvalKeyboard(Long orderId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("Да")
                        .callbackData("table_booking:confirm:" + orderId)
                        .build(),
                InlineKeyboardButton.builder()
                        .text("Нет")
                        .callbackData("table_booking:reject:" + orderId)
                        .build()
        ));
        if (reviewAtTheBill()) {
            rows.add(List.of(billButton(orderId)));
        }
        if (staffEditEnabled) {
            rows.add(List.of(editButton(orderId)));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * «🪑 Гость сел», «Счёт» and/or «✏️ Изменить» under the confirmed card, or nothing: the card stays as it was unless
     * the guest's seating is marked here (#151), the review is asked at the bill or the hostess may edit bookings.
     */
    InlineKeyboardMarkup billKeyboard(Long orderId) {
        return confirmedCardKeyboard(orderId, seatedEnabled);
    }

    /** The same card once the guest sat down: «🪑 Гость сел» is gone, «Счёт» and «✏️ Изменить» stay. */
    public InlineKeyboardMarkup seatedCardKeyboard(Long orderId) {
        return confirmedCardKeyboard(orderId, false);
    }

    private InlineKeyboardMarkup confirmedCardKeyboard(Long orderId, boolean withSeated) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (withSeated) {
            rows.add(List.of(seatedButton(orderId)));
        }
        if (reviewAtTheBill()) {
            rows.add(List.of(billButton(orderId)));
        }
        if (staffEditEnabled) {
            rows.add(List.of(editButton(orderId)));
        }
        if (rows.isEmpty()) {
            return null;
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardButton seatedButton(Long orderId) {
        return InlineKeyboardButton.builder()
                .text("🪑 Гость сел")
                .callbackData(HostessSeatedService.callbackData(orderId))
                .build();
    }

    private InlineKeyboardButton billButton(Long orderId) {
        return InlineKeyboardButton.builder()
                .text("Счёт")
                .callbackData(HostessBillRequestService.callbackData(orderId))
                .build();
    }

    private InlineKeyboardButton editButton(Long orderId) {
        return InlineKeyboardButton.builder()
                .text("✏️ Изменить")
                .callbackData(HostessBookingEditService.callbackData(orderId))
                .build();
    }

    private boolean reviewAtTheBill() {
        return ReviewMoment.parse(reviewMoment) == ReviewMoment.ON_BILL;
    }

    void setReviewMoment(String reviewMoment) {
        this.reviewMoment = reviewMoment;
    }

    void setStaffEditEnabled(boolean staffEditEnabled) {
        this.staffEditEnabled = staffEditEnabled;
    }

    void setSeatedEnabled(boolean seatedEnabled) {
        this.seatedEnabled = seatedEnabled;
    }

    void setSystemNotifier(TelegramSystemNotifier systemNotifier) {
        this.systemNotifier = systemNotifier;
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

    /** «Стол 5 (5)», «Стол VIP 13 (13)» or «не выбран», as every card names the table. */
    static String tableName(TableReservationOrder order) {
        if (order.tableCode() == null) {
            return "не выбран";
        }
        if (order.tableDisplayName() == null || order.tableDisplayName().isBlank()) {
            return "Стол " + order.tableCode();
        }
        return humanTableDisplayName(order.tableDisplayName()) + " (" + order.tableCode() + ")";
    }

    static String tableName(VenueTable table) {
        if (table == null || table.tableCode() == null) {
            return "не выбран";
        }
        if (table.displayName() == null || table.displayName().isBlank()) {
            return "Стол " + table.tableCode();
        }
        return humanTableDisplayName(table.displayName()) + " (" + table.tableCode() + ")";
    }

    static String humanTableDisplayName(String displayName) {
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
        if (status == TableReservationStatus.SEATED) {
            return "гость сел";
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

    private static String formatTime(java.time.Instant instant) {
        return instant == null ? "—" : TIME.format(instant);
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
