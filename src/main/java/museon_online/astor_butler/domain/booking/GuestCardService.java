package museon_online.astor_butler.domain.booking;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillLine;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.billing.VisitReview;
import museon_online.astor_butler.domain.billing.VisitReviewRepository;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.preference.GuestPreference;
import museon_online.astor_butler.domain.preference.GuestPreferenceCategory;
import museon_online.astor_butler.domain.preference.GuestPreferenceRepository;
import museon_online.astor_butler.domain.tips.TipStaffMember;
import museon_online.astor_butler.domain.tips.TipsPersonalProperties;
import museon_online.astor_butler.domain.tips.TipsPersonalRepository;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guest card for the waiter (#163): the moment a booking becomes SEATED (#151 — the hostess button or Presto), the
 * staff chat gets «🧾 Стол N — гость сел» under the hostess card of the booking, so the waiter knows the table before
 * walking up to it. Everything on the card comes from the guest's own words to the bot and the guest's own orders
 * through it: today's lunch wish (#180), what the guest asked the bot to remember (preferences), earlier visits and
 * lunch orders of the same chat, the stars the guest gave last time. Allergens are never inferred: the line reads
 * «не указаны» unless the guest wrote about them. No phone, no username, no spend; a first name only when the booking
 * carries one. Off by default ({@code astor.booking.waiter-guest-card.enabled}); one card per booking, whichever path
 * seated it.
 *
 * <p>A personal copy to the waiter of the table (#161 assignment) is not sent yet: the staff directory
 * ({@code astor_staff_members}) has no Telegram chat id for its members, so there is nowhere to send it.
 */
@Service
@Slf4j
public class GuestCardService {

    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Yekaterinburg");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(VENUE_ZONE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(VENUE_ZONE);

    /** How far back the card looks: this many latest bookings, bills and reviews of the chat. */
    static final int HISTORY_LIMIT = 50;
    static final int PREFERENCES_LIMIT = 30;
    /** At most this many preferences of one kind on a line, newest first. */
    static final int PER_LINE = 3;
    static final int FAVOURITES = 2;
    /** The longest a guest's own text gets on the card. */
    static final int TEXT_LIMIT = 120;

    /** «Пожелание гостя: без лука — согласовать с кухней» as the lunch order writes it into the booking comment (#180). */
    private static final Pattern WISH = Pattern.compile(
            Pattern.quote(BusinessLunchService.WISH_PREFIX) + "(.+?)" + Pattern.quote(BusinessLunchService.WISH_SUFFIX));
    /** The guest's own words about an allergy; nothing else makes the allergens line. */
    private static final Pattern ALLERGY_WORDS = Pattern.compile("аллерг|непереносим", Pattern.CASE_INSENSITIVE);

    private final WaiterGuestCardProperties properties;
    private final TableReservationRepository reservations;
    private final GuestBillRepository bills;
    private final VisitReviewRepository reviews;
    private final GuestPreferenceRepository preferences;
    private final TipsPersonalRepository directory;
    private final TipsPersonalProperties tipsProperties;
    private final BookingHostessStateStorage hostessState;
    private final ObjectProvider<TelegramBot> telegramBotProvider;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled = true;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String defaultHostessChatId;

    public GuestCardService(WaiterGuestCardProperties properties,
                            TableReservationRepository reservations,
                            GuestBillRepository bills,
                            VisitReviewRepository reviews,
                            GuestPreferenceRepository preferences,
                            TipsPersonalRepository directory,
                            TipsPersonalProperties tipsProperties,
                            BookingHostessStateStorage hostessState,
                            ObjectProvider<TelegramBot> telegramBotProvider) {
        this.properties = properties;
        this.reservations = reservations;
        this.bills = bills;
        this.reviews = reviews;
        this.preferences = preferences;
        this.directory = directory;
        this.tipsProperties = tipsProperties;
        this.hostessState = hostessState;
        this.telegramBotProvider = telegramBotProvider;
    }

    /**
     * The booking just became SEATED: build the card and send it once. Called from the seating path after the status is
     * written; whatever goes wrong here is logged and never undoes the seating. A second call for the same booking
     * (a repeated event, the other seating path) sends nothing.
     */
    public void onSeated(TableReservationOrder order) {
        if (!properties.isEnabled() || order == null || order.id() == null) {
            return;
        }
        if (!firstTime(order.id())) {
            log.debug("Guest card for reservation {} was already sent", order.id());
            return;
        }
        GuestCard card = build(order);
        deliver(order, card);
    }

    /** The card from what the bot holds about the chat of the booking; a source that fails leaves its line empty. */
    GuestCard build(TableReservationOrder order) {
        Long chatId = order.chatId();
        Instant reference = order.seatedAt() != null ? order.seatedAt() : Instant.now();

        List<TableReservationOrder> past = chatId == null ? List.of() : read(() -> reservations.findOrdersByChatId(chatId, HISTORY_LIMIT)).stream()
                .filter(earlier -> !Objects.equals(earlier.id(), order.id()))
                .filter(earlier -> earlier.status() == TableReservationStatus.SEATED || earlier.status() == TableReservationStatus.CONFIRMED)
                .filter(earlier -> earlier.requestedStartAt() != null && earlier.requestedStartAt().isBefore(reference))
                .toList();
        Instant lastVisit = past.stream()
                .map(earlier -> earlier.seatedAt() != null ? earlier.seatedAt() : earlier.requestedStartAt())
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<GuestPreference> remembered = chatId == null ? List.of() : read(() -> preferences.findActiveByChatId(chatId, PREFERENCES_LIMIT));
        String wish = wishOf(order.guestComment());

        List<GuestBill> chatBills = chatId == null ? List.of() : read(() -> bills.findByChatId(chatId, HISTORY_LIMIT));
        Set<Long> todaysBills = new HashSet<>();
        for (GuestBill bill : chatBills) {
            if (Objects.equals(bill.tableReservationId(), order.id())) {
                todaysBills.add(bill.id());
            }
        }
        List<String> favourites = favourites(chatBills, todaysBills);

        Integer lastStars = chatId == null ? null : read(() -> reviews.findByChatId(chatId, HISTORY_LIMIT)).stream()
                .filter(review -> review.stars() != null && !todaysBills.contains(review.billId()))
                .map(VisitReview::stars)
                .findFirst()
                .orElse(null);

        return new GuestCard(
                order.id(),
                TableReservationNotificationService.tableName(order),
                order.seatedAt(),
                order.partySize(),
                firstName(order.guestName()),
                past.size(),
                lastVisit,
                wish,
                allergens(remembered, wish),
                texts(remembered, GuestPreferenceCategory.DRINK),
                texts(remembered, GuestPreferenceCategory.FOOD),
                texts(remembered, GuestPreferenceCategory.SERVICE, GuestPreferenceCategory.COMMUNICATION, GuestPreferenceCategory.GENERAL),
                favourites,
                lastStars,
                waiterOfTable(order, reference)
        );
    }

    /** The card as the staff chat reads it: at most ten lines, HTML, every guest's word escaped. */
    String render(GuestCard card) {
        List<String> lines = new ArrayList<>();
        lines.add("🧾 <b>" + escape(card.tableName()) + " — гость сел</b> " + time(card.seatedAt())
                + " · гостей: " + (card.partySize() == null ? "—" : card.partySize()) + " · бронь #" + card.orderId());
        String visits = card.firstVisit()
                ? "первый визит в боте"
                : "визитов: " + card.pastVisits() + (card.lastVisit() == null ? "" : ", последний " + DATE.format(card.lastVisit()));
        lines.add(card.firstName() == null
                ? "<b>Визиты:</b> " + visits
                : "<b>Гость:</b> " + escape(card.firstName()) + " · " + visits);
        if (card.wish() != null) {
            lines.add("<b>Пожелание к заказу:</b> " + escape(card.wish()));
        }
        lines.add("<b>Аллергены:</b> " + (card.allergens() == null ? "не указаны" : escape(card.allergens())));
        if (!card.drinks().isEmpty()) {
            lines.add("<b>Напитки:</b> " + escape(String.join("; ", card.drinks())));
        }
        if (!card.food().isEmpty()) {
            lines.add("<b>Еда:</b> " + escape(String.join("; ", card.food())));
        }
        if (!card.favourites().isEmpty()) {
            lines.add("<b>Любимое:</b> " + escape(String.join(", ", card.favourites())));
        }
        if (card.lastStars() != null) {
            lines.add("<b>Прошлый визит:</b> " + card.lastStars() + " из 5");
        }
        if (!card.other().isEmpty()) {
            lines.add("<b>Ещё:</b> " + escape(String.join("; ", card.other())));
        }
        if (card.waiter() != null) {
            lines.add("<b>Официант стола:</b> " + escape(card.waiter()));
        }
        return String.join("\n", lines);
    }

    /**
     * To the staff chat of the booking, under its hostess card when that card is remembered and lives in the same chat.
     * The waiter's personal chat is not known to the bot (see the class note), so the staff chat is the only route.
     */
    private void deliver(TableReservationOrder order, GuestCard card) {
        String chatId = hostessChatId(order);
        Integer replyTo = hostessCard(order.id())
                .filter(ref -> ref.chatId() != null && ref.chatId().equals(chatId))
                .map(BookingHostessStateStorage.CardRef::messageId)
                .orElse(null);
        Message sent = send(chatId, render(card), replyTo);
        if (sent != null && card.waiter() != null) {
            log.info("Guest card for reservation {} went to the staff chat; no personal copy for the waiter of the table: "
                    + "the staff directory has no Telegram chat id (#163)", order.id());
        }
    }

    /** True the first time only; with the marker store down the card is sent anyway, once is better than never. */
    private boolean firstTime(Long orderId) {
        try {
            return hostessState.markGuestCardSent(orderId);
        } catch (RuntimeException e) {
            log.debug("Guest card marker was not written: orderId={}, reason={}", orderId, e.toString());
            return true;
        }
    }

    private Optional<BookingHostessStateStorage.CardRef> hostessCard(Long orderId) {
        try {
            return hostessState.hostessCard(orderId);
        } catch (RuntimeException e) {
            log.debug("Hostess card reference was not read: orderId={}, reason={}", orderId, e.toString());
            return Optional.empty();
        }
    }

    private Message send(String chatId, String text, Integer replyToMessageId) {
        if (!telegramEnabled || !notificationsEnabled || chatId == null || chatId.isBlank()) {
            log.debug("Guest card skipped: botEnabled={}, notificationsEnabled={}, chatConfigured={}",
                    telegramEnabled, notificationsEnabled, chatId != null && !chatId.isBlank());
            return null;
        }
        try {
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                return null;
            }
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML");
            if (replyToMessageId != null) {
                builder.replyToMessageId(replyToMessageId).allowSendingWithoutReply(true);
            }
            return bot.execute(builder.build());
        } catch (Exception e) {
            log.warn("Guest card was not sent to the staff chat: reason={}", e.getMessage());
            return null;
        }
    }

    // ---- the sources ----

    /** The wish of today's lunch order (#180), as the booking comment carries it; null for a booking without one. */
    static String wishOf(String guestComment) {
        if (guestComment == null || guestComment.isBlank()) {
            return null;
        }
        Matcher matcher = WISH.matcher(guestComment);
        if (!matcher.find()) {
            return null;
        }
        return clip(matcher.group(1));
    }

    /**
     * Only the guest's own words: ALLERGY preferences the guest asked the bot to remember, and today's wish when it
     * speaks of an allergy itself. Anything else — a dish left out, a food preference — is not an allergen.
     */
    static String allergens(List<GuestPreference> remembered, String wish) {
        List<String> words = new ArrayList<>(texts(remembered, GuestPreferenceCategory.ALLERGY));
        if (wish != null && ALLERGY_WORDS.matcher(wish).find() && !words.contains(wish)) {
            words.add(wish);
        }
        return words.isEmpty() ? null : String.join("; ", words);
    }

    /** The texts of the given kinds, newest first, at most {@link #PER_LINE}, each clipped. */
    static List<String> texts(List<GuestPreference> remembered, GuestPreferenceCategory... categories) {
        Set<GuestPreferenceCategory> wanted = Set.of(categories);
        List<String> texts = new ArrayList<>();
        for (GuestPreference preference : remembered) {
            if (preference.category() == null || !wanted.contains(preference.category())) {
                continue;
            }
            String text = clip(preference.preferenceText());
            if (text != null && !texts.contains(text)) {
                texts.add(text);
            }
            if (texts.size() == PER_LINE) {
                break;
            }
        }
        return List.copyOf(texts);
    }

    /**
     * The dishes ordered most across the guest's earlier lunch orders, by portions; a tie goes to the more recent dish.
     * A set lunch is counted by its dishes, not by the set line that prices them; today's own order does not count.
     */
    private List<String> favourites(List<GuestBill> chatBills, Set<Long> todaysBills) {
        Map<String, Integer> portions = new LinkedHashMap<>();
        for (GuestBill bill : chatBills) {
            if (todaysBills.contains(bill.id())) {
                continue;
            }
            List<GuestBillLine> lines = read(() -> bills.findLines(bill.id()));
            boolean setLunch = lines.stream().anyMatch(GuestBillLine::included);
            for (GuestBillLine line : lines) {
                if (setLunch && !line.included()) {
                    continue;
                }
                String title = clip(line.title());
                if (title != null) {
                    portions.merge(title, Math.max(1, line.quantity()), Integer::sum);
                }
            }
        }
        return portions.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(FAVOURITES)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** The waiter assigned to the table for the venue's day of the seating (#161); null without one. */
    private String waiterOfTable(TableReservationOrder order, Instant reference) {
        if (order.tableCode() == null || order.tableCode().isBlank()) {
            return null;
        }
        LocalDate day = LocalDate.ofInstant(reference, tipsProperties.zone());
        try {
            return directory.waiterOfTable(tipsProperties.tenantOrDefault(), order.tableCode().trim(), day)
                    .map(TipStaffMember::displayName)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Waiter of table {} was not read for the guest card: {}", order.tableCode(), e.toString());
            return null;
        }
    }

    /** The first word of the name the guest gave; nothing when it looks like a phone or a username rather than a name. */
    static String firstName(String guestName) {
        if (guestName == null || guestName.isBlank()) {
            return null;
        }
        String first = guestName.trim().split("\\s+")[0];
        if (first.isBlank() || first.chars().anyMatch(Character::isDigit) || first.contains("@")) {
            return null;
        }
        return first;
    }

    private static <T> List<T> read(Supplier<List<T>> source) {
        try {
            List<T> result = source.get();
            return result == null ? List.of() : result;
        } catch (RuntimeException e) {
            log.warn("A source of the guest card is not readable, its line stays empty: {}", e.toString());
            return List.of();
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= TEXT_LIMIT ? normalized : normalized.substring(0, TEXT_LIMIT - 1) + "…";
    }

    private String hostessChatId(TableReservationOrder order) {
        return order.hostessChatId() == null || order.hostessChatId().isBlank() ? defaultHostessChatId : order.hostessChatId();
    }

    private static String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant);
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    void setTelegramEnabled(boolean telegramEnabled) {
        this.telegramEnabled = telegramEnabled;
    }

    void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    void setDefaultHostessChatId(String defaultHostessChatId) {
        this.defaultHostessChatId = defaultHostessChatId;
    }
}
