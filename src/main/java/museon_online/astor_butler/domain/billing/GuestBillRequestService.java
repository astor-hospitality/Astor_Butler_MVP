package museon_online.astor_butler.domain.billing;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.shift.ButlerPauseService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The guest asks for the bill in the bot (#153 A2): «счёт», «чек», «посчитайте» and the like, typed while no dialogue
 * step is waiting for an answer. Does for the guest's own words what the hostess «Счёт» button does for the hostess
 * (same {@link HostessBillRequestService#askReview} step, so the review is asked once per visit whoever triggered it)
 * and, on top of that, sends the staff chat a card «стол N просит счёт» so the waiter brings the bill.
 *
 * <p>Only a guest with a visit today — a confirmed or still-waiting reservation whose time falls on today's date at
 * the venue — is served; anyone else is told so and offered the menu. A second «счёт» inside the repeat window sends
 * the team nothing and says «уже передал». Off by default ({@code ASTOR_GUEST_BILL_REQUEST_ENABLED}); while the
 * shift manager has stopped Butler (#134) the words go to the usual flow, which already hands them to the team.
 */
@Service
@Slf4j
public class GuestBillRequestService {

    static final String TRIGGER = "trigger=guest";
    static final String ACTION = "GUEST_BILL_REQUEST";
    static final String REPLY_SENT = "Передал команде, счёт сейчас принесут.";
    static final String REPLY_REPEATED = "Уже передал — счёт сейчас принесут.";
    static final String REPLY_NO_VISIT = "Не вижу у вас активного визита на сегодня, поэтому счёт передать некому. "
            + "Могу показать меню или помочь с бронью стола.";
    static final String REPLY_NOT_DELIVERED = "Не получилось передать команде — пожалуйста, позовите официанта.";

    /** The states in which a guest's text is free words, not an answer to a step of some dialogue. */
    static final Set<BotState> OPEN_STATES = EnumSet.of(
            BotState.READY_FOR_DIALOG,
            BotState.AI_FALLBACK,
            BotState.MENU_ASSETS_DELIVERED,
            BotState.QUIET_GUIDE_DELIVERED,
            BotState.TABLE_BOOKING_CONFIRMED,
            BotState.TABLE_BOOKING_REJECTED,
            BotState.TABLE_BOOKING_CANCELLED,
            BotState.EVENT_BOOKING_SENT,
            BotState.MANAGER_HELP_SENT,
            BotState.FEEDBACK_SENT,
            BotState.PREFERENCE_SAVED,
            BotState.CONCIERGE_SENT,
            BotState.MERCH_SENT,
            BotState.SAFE_EXIT
    );

    private final GuestBillRequestProperties properties;
    private final ReviewProperties reviewProperties;
    private final HostessBillRequestService billRequests;
    private final TableReservationRepository reservations;
    private final TableReservationNotificationService notifications;
    private final GuestBillNotifier notifier;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Value("${astor.redis.key-prefix:astor}")
    private String keyPrefix = "astor";

    /** The shift manager's kill switch (#134): optional so the constructor and its tests stay as they are. */
    @Autowired(required = false)
    private ButlerPauseService butlerPause;

    @Autowired
    public GuestBillRequestService(GuestBillRequestProperties properties, ReviewProperties reviewProperties,
                                   HostessBillRequestService billRequests, TableReservationRepository reservations,
                                   TableReservationNotificationService notifications, GuestBillNotifier notifier,
                                   StringRedisTemplate redis) {
        this(properties, reviewProperties, billRequests, reservations, notifications, notifier, redis, Clock.systemUTC());
    }

    GuestBillRequestService(GuestBillRequestProperties properties, ReviewProperties reviewProperties,
                            HostessBillRequestService billRequests, TableReservationRepository reservations,
                            TableReservationNotificationService notifications, GuestBillNotifier notifier,
                            StringRedisTemplate redis, Clock clock) {
        this.properties = properties;
        this.reviewProperties = reviewProperties;
        this.billRequests = billRequests;
        this.reservations = reservations;
        this.notifications = notifications;
        this.notifier = notifier;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * The bot's answer when the message asks for the bill; empty when it does not, or when the feature is off, the
     * guest is in the middle of a dialogue step or Butler is paused — then the usual flow answers as before.
     */
    public Optional<OutgoingMessage> handle(IncomingMessage incoming, BotState currentState, String text) {
        if (!properties.isEnabled() || incoming == null || incoming.chatId() == null || incoming.chatId() <= 0) {
            return Optional.empty();
        }
        if (!isOpen(currentState) || !GuestBillIntent.matches(text)) {
            return Optional.empty();
        }
        if (butlerPause != null && butlerPause.paused()) {
            log.info("Guest {} asked for the bill while Butler is paused: left to the usual flow", incoming.chatId());
            return Optional.empty();
        }
        BotState state = currentState == null ? BotState.READY_FOR_DIALOG : currentState.canonical();
        Optional<TableReservationOrder> visit = visitToday(incoming.chatId());
        if (visit.isEmpty()) {
            log.info("Guest {} asked for the bill, but has no visit today", incoming.chatId());
            return Optional.of(reply(incoming, state, REPLY_NO_VISIT, List.of(ACTION, "NO_ACTIVE_VISIT"), Map.of()));
        }
        TableReservationOrder order = visit.get();
        if (!firstAskWithinWindow(order.id())) {
            log.info("Guest {} asked for the bill of reservation {} again within the window: nothing re-sent", incoming.chatId(), order.id());
            return Optional.of(reply(incoming, state, REPLY_REPEATED, List.of(ACTION, "ALREADY_SENT"),
                    Map.of("reservationId", (Object) order.id())));
        }

        boolean cardSent = notifications.notifyHostessGuestAskedForBill(order);
        if (!cardSent) {
            log.warn("Guest {} asked for the bill of reservation {}, but the staff chat could not be reached", incoming.chatId(), order.id());
            forget(order.id());
            return Optional.of(reply(incoming, state, REPLY_NOT_DELIVERED, List.of(ACTION, "STAFF_NOT_REACHED"),
                    Map.of("reservationId", (Object) order.id())));
        }
        List<String> actions = new ArrayList<>(List.of(ACTION, "STAFF_NOTIFIED"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reservationId", order.id());

        // The acknowledgement goes first, then the review question (when it is asked at the bill), so the guest reads
        // the answer to what they asked before the stars. When Telegram did not take it, the FSM reply carries it.
        boolean replied = notifier.billRequestReply(incoming.chatId(), REPLY_SENT);
        if (reviewProperties.onBill()) {
            HostessBillRequestService.ReviewAsk review = billRequests.askReview(order.id(), TRIGGER);
            actions.add("REVIEW_" + review.name());
            metadata.put("review", review.name());
            log.info("Guest {} asked for the bill of reservation {}: review {}", incoming.chatId(), order.id(), review);
        }
        return Optional.of(reply(incoming, state, replied ? "" : REPLY_SENT, actions, metadata));
    }

    /** The guest's reservation whose time falls on today's date at the venue; a confirmed one first, then the nearest. */
    Optional<TableReservationOrder> visitToday(Long chatId) {
        ZoneId zone = zone();
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        return reservations.findActiveOrdersByChatId(chatId).stream()
                .filter(order -> order.status() == TableReservationStatus.CONFIRMED
                        || order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION)
                .filter(order -> order.requestedStartAt() != null
                        && order.requestedStartAt().atZone(zone).toLocalDate().equals(today))
                .min(Comparator.<TableReservationOrder>comparingInt(order -> order.status() == TableReservationStatus.CONFIRMED ? 0 : 1)
                        .thenComparingLong(order -> Math.abs(Duration.between(now, order.requestedStartAt()).toMinutes())));
    }

    static boolean isOpen(BotState state) {
        return state != null && OPEN_STATES.contains(state.canonical());
    }

    /** True the first time only: the caller that gets {@code true} is the one to tell the team. A Redis hiccup reads as the first time. */
    private boolean firstAskWithinWindow(Long orderId) {
        try {
            Boolean set = redis.opsForValue().setIfAbsent(key(orderId), clock.instant().toString(),
                    Duration.ofMinutes(Math.max(1, properties.getRepeatWindowMinutes())));
            return !Boolean.FALSE.equals(set);
        } catch (RuntimeException e) {
            log.warn("Guest bill request window could not be checked for reservation {}: {}", orderId, e.toString());
            return true;
        }
    }

    private void forget(Long orderId) {
        try {
            redis.delete(key(orderId));
        } catch (RuntimeException e) {
            log.debug("Guest bill request mark could not be dropped for reservation {}: {}", orderId, e.toString());
        }
    }

    String key(Long orderId) {
        return (keyPrefix == null || keyPrefix.isBlank() ? "astor" : keyPrefix) + ":billing:guest-bill-request:" + orderId;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(properties.getTimezone());
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Yekaterinburg");
        }
    }

    private static OutgoingMessage reply(IncomingMessage incoming, BotState state, String text, List<String> actions,
                                         Map<String, Object> metadata) {
        return OutgoingMessage.of(
                incoming,
                text,
                state.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                actions
        ).withMetadata(metadata);
    }

    void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    void setButlerPause(ButlerPauseService butlerPause) {
        this.butlerPause = butlerPause;
    }
}
