package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.billing.BillPayState;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillLine;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.billing.VisitReview;
import museon_online.astor_butler.domain.billing.VisitReviewRepository;
import museon_online.astor_butler.domain.preference.GuestPreference;
import museon_online.astor_butler.domain.preference.GuestPreferenceCategory;
import museon_online.astor_butler.domain.preference.GuestPreferenceRepository;
import museon_online.astor_butler.domain.preference.GuestPreferenceStatus;
import museon_online.astor_butler.domain.tips.TipStaffMember;
import museon_online.astor_butler.domain.tips.TipsPersonalProperties;
import museon_online.astor_butler.domain.tips.TipsPersonalRepository;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The guest card for the waiter after «гость сел» (#163): what goes on it and where it comes from, what never goes on
 * it, how it reaches the staff chat, and that it goes once. 09:32 UTC is 14:32 at the venue.
 */
class GuestCardServiceTest {

    private static final long CHAT = 1773317437L;
    private static final String PHONE = "+79990000000";
    private static final Instant START = Instant.parse("2026-10-12T08:00:00Z");
    private static final Instant SEATED_AT = Instant.parse("2026-10-12T09:32:00Z");

    private final WaiterGuestCardProperties properties = new WaiterGuestCardProperties();
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final GuestBillRepository bills = mock(GuestBillRepository.class);
    private final VisitReviewRepository reviews = mock(VisitReviewRepository.class);
    private final GuestPreferenceRepository preferences = mock(GuestPreferenceRepository.class);
    private final TipsPersonalRepository directory = mock(TipsPersonalRepository.class);
    private final TipsPersonalProperties tips = new TipsPersonalProperties();
    private final BookingHostessStateStorage hostessState = mock(BookingHostessStateStorage.class);
    private final TelegramBot bot = mock(TelegramBot.class);
    private final GuestCardService service = new GuestCardService(
            properties, reservations, bills, reviews, preferences, directory, tips, hostessState, provider(bot));

    private final TableReservationOrder seated = order(44L, "Наталья Петрова", null, TableReservationStatus.SEATED, START, SEATED_AT);

    @BeforeEach
    void theFeatureIsOnAndTheBotIsUp() throws Exception {
        properties.setEnabled(true);
        service.setTelegramEnabled(true);
        service.setDefaultHostessChatId("500");
        when(hostessState.markGuestCardSent(44L)).thenReturn(true);
        when(bot.execute(any(SendMessage.class))).thenReturn(new Message());
    }

    @Test
    void withTheFlagOffNothingIsReadAndNothingIsSent() {
        properties.setEnabled(false);

        service.onSeated(seated);

        verifyNoInteractions(reservations, bills, reviews, preferences, directory, hostessState, bot);
    }

    @Test
    void aFirstVisitGivesAShortCardWithoutPhoneUsernameOrSurname() throws Exception {
        service.onSeated(seated);

        SendMessage sent = sent();
        assertThat(sent.getChatId()).isEqualTo("500");
        assertThat(sent.getParseMode()).isEqualTo("HTML");
        assertThat(sent.getReplyToMessageId()).isNull();
        assertThat(sent.getText()).isEqualTo("""
                🧾 <b>Стол 5 (5) — гость сел</b> 14:32 · гостей: 2 · бронь #44
                <b>Гость:</b> Наталья · первый визит в боте
                <b>Аллергены:</b> не указаны""");
        assertThat(sent.getText()).doesNotContain(PHONE, String.valueOf(CHAT), "@", "Петрова");
        assertThat(sent.getText().lines().count()).isLessThanOrEqualTo(10);
    }

    @Test
    void aNameThatLooksLikeAPhoneOrAUsernameIsNotAName() {
        assertThat(GuestCardService.firstName("Наталья Петрова")).isEqualTo("Наталья");
        assertThat(GuestCardService.firstName("  Иван ")).isEqualTo("Иван");
        assertThat(GuestCardService.firstName(PHONE)).isNull();
        assertThat(GuestCardService.firstName("@natasha")).isNull();
        assertThat(GuestCardService.firstName(" ")).isNull();
        assertThat(GuestCardService.firstName(null)).isNull();

        String text = service.render(service.build(order(44L, "@natasha", null, TableReservationStatus.SEATED, START, SEATED_AT)));
        assertThat(text).contains("<b>Визиты:</b> первый визит в боте").doesNotContain("@natasha", "Гость:");
    }

    @Test
    void aRepeatGuestGetsVisitsWishPreferencesFavouritesStarsAndTheWaiterOfTheTable() throws Exception {
        String comment = "Бизнес-ланч: 2 × «Сет дня», 650 ₽. Борщ, Цезарь. Пожелание гостя: без лука — согласовать с кухней. "
                + "Пожелание: у окна. В Saby внести вручную.";
        TableReservationOrder today = order(44L, "Наталья Петрова", comment, TableReservationStatus.SEATED, START, SEATED_AT);
        when(reservations.findOrdersByChatId(CHAT, GuestCardService.HISTORY_LIMIT)).thenReturn(List.of(
                today,
                order(45L, "Наталья", null, TableReservationStatus.CONFIRMED, Instant.parse("2026-10-20T08:00:00Z"), null),
                order(31L, "Наталья", null, TableReservationStatus.SEATED, Instant.parse("2026-10-02T12:00:00Z"), Instant.parse("2026-10-02T12:10:00Z")),
                order(30L, "Наталья", null, TableReservationStatus.CONFIRMED, Instant.parse("2026-09-20T08:00:00Z"), null),
                order(29L, "Наталья", null, TableReservationStatus.CANCELLED, Instant.parse("2026-09-10T08:00:00Z"), null),
                order(28L, "Наталья", null, TableReservationStatus.EXPIRED, Instant.parse("2026-09-01T08:00:00Z"), null)));
        when(preferences.findActiveByChatId(CHAT, GuestCardService.PREFERENCES_LIMIT)).thenReturn(List.of(
                preference(GuestPreferenceCategory.DRINK, "красное сухое"),
                preference(GuestPreferenceCategory.FOOD, "не ем острое"),
                preference(GuestPreferenceCategory.SEATING, "тихий стол"),
                preference(GuestPreferenceCategory.SERVICE, "не подходить часто")));
        when(bills.findByChatId(CHAT, GuestCardService.HISTORY_LIMIT)).thenReturn(List.of(bill(3L, 44L), bill(2L, 31L), bill(1L, 30L)));
        when(bills.findLines(3L)).thenReturn(List.of(line(3L, "Стейк", 2, false)));
        when(bills.findLines(2L)).thenReturn(List.of(line(2L, "Борщ", 1, false), line(2L, "Паста", 1, false)));
        when(bills.findLines(1L)).thenReturn(List.of(
                line(1L, "Сет дня", 2, false),
                line(1L, "Борщ", 2, true), line(1L, "Цезарь", 2, true), line(1L, "Компот", 2, true)));
        when(reviews.findByChatId(CHAT, GuestCardService.HISTORY_LIMIT)).thenReturn(List.of(
                review(3L, 1), review(2L, 4), review(1L, 5)));
        when(directory.waiterOfTable("AERIS", "5", LocalDate.of(2026, 10, 12)))
                .thenReturn(Optional.of(new TipStaffMember("kirill", "Кирилл", "WAITER", true, true, null, null)));

        service.onSeated(today);

        SendMessage sent = sent();
        assertThat(sent.getText()).isEqualTo("""
                🧾 <b>Стол 5 (5) — гость сел</b> 14:32 · гостей: 2 · бронь #44
                <b>Гость:</b> Наталья · визитов: 2, последний 02.10.2026
                <b>Пожелание к заказу:</b> без лука
                <b>Аллергены:</b> не указаны
                <b>Напитки:</b> красное сухое
                <b>Еда:</b> не ем острое
                <b>Любимое:</b> Борщ, Цезарь
                <b>Прошлый визит:</b> 4 из 5
                <b>Ещё:</b> не подходить часто
                <b>Официант стола:</b> Кирилл""");
        assertThat(sent.getText()).doesNotContain(PHONE, String.valueOf(CHAT), "Стейк", "Сет дня", "тихий стол", "у окна");
        assertThat(sent.getText().lines().count()).isLessThanOrEqualTo(10);
        // The waiter has no Telegram chat in the directory: the staff chat is the only message that leaves.
        verify(bot, times(1)).execute(any(SendMessage.class));
    }

    @Test
    void allergensComeOnlyFromTheGuestsOwnWords() {
        String withoutOnion = "Бизнес-ланч: Борщ × 1. Пожелание гостя: без лука — согласовать с кухней.";
        String withAllergy = "Бизнес-ланч: Борщ × 1. Пожелание гостя: аллергия на орехи, без лука — согласовать с кухней.";

        // A dish left out and a food preference are not an allergen.
        when(preferences.findActiveByChatId(CHAT, GuestCardService.PREFERENCES_LIMIT))
                .thenReturn(List.of(preference(GuestPreferenceCategory.FOOD, "люблю острое")));
        GuestCard card = service.build(order(44L, "Наталья", withoutOnion, TableReservationStatus.SEATED, START, SEATED_AT));
        assertThat(card.wish()).isEqualTo("без лука");
        assertThat(card.allergens()).isNull();
        assertThat(service.render(card)).contains("<b>Аллергены:</b> не указаны");

        // The guest asked the bot to remember an allergy.
        when(preferences.findActiveByChatId(CHAT, GuestCardService.PREFERENCES_LIMIT))
                .thenReturn(List.of(preference(GuestPreferenceCategory.ALLERGY, "аллергия на орехи")));
        card = service.build(order(44L, "Наталья", withoutOnion, TableReservationStatus.SEATED, START, SEATED_AT));
        assertThat(card.allergens()).isEqualTo("аллергия на орехи");
        assertThat(service.render(card)).contains("<b>Аллергены:</b> аллергия на орехи");

        // Today's wish speaks of an allergy itself: it is quoted as the guest wrote it, next to the preference.
        card = service.build(order(44L, "Наталья", withAllergy, TableReservationStatus.SEATED, START, SEATED_AT));
        assertThat(card.wish()).isEqualTo("аллергия на орехи, без лука");
        assertThat(card.allergens()).isEqualTo("аллергия на орехи; аллергия на орехи, без лука");

        // A plain table booking carries no lunch wish: its comment is not read into the card.
        when(preferences.findActiveByChatId(CHAT, GuestCardService.PREFERENCES_LIMIT)).thenReturn(List.of());
        card = service.build(order(44L, "Наталья", "столик у окна, аллергия на орехи, " + PHONE, TableReservationStatus.SEATED, START, SEATED_AT));
        assertThat(card.wish()).isNull();
        assertThat(card.allergens()).isNull();
        assertThat(service.render(card)).doesNotContain(PHONE, "у окна");
    }

    @Test
    void theGuestsWordsAreEscapedAndClipped() {
        String wish = "x".repeat(200);
        when(preferences.findActiveByChatId(CHAT, GuestCardService.PREFERENCES_LIMIT))
                .thenReturn(List.of(preference(GuestPreferenceCategory.DRINK, "<b>джин</b> & тоник")));
        GuestCard card = service.build(order(44L, "Наталья", "Пожелание гостя: " + wish + " — согласовать с кухней.",
                TableReservationStatus.SEATED, START, SEATED_AT));

        String text = service.render(card);
        assertThat(text).contains("&lt;b&gt;джин&lt;/b&gt; &amp; тоник");
        assertThat(card.wish()).hasSize(GuestCardService.TEXT_LIMIT).endsWith("…");
    }

    @Test
    void theCardAnswersTheHostessCardWhenItIsRememberedInTheSameChat() throws Exception {
        when(hostessState.hostessCard(44L)).thenReturn(Optional.of(new BookingHostessStateStorage.CardRef("500", 100)));
        service.onSeated(seated);
        assertThat(sent().getReplyToMessageId()).isEqualTo(100);
        assertThat(sent().getAllowSendingWithoutReply()).isTrue();

        // Remembered in another chat (the manager's, after an escalation): a plain message to the staff chat.
        when(hostessState.markGuestCardSent(45L)).thenReturn(true);
        when(hostessState.hostessCard(45L)).thenReturn(Optional.of(new BookingHostessStateStorage.CardRef("777", 101)));
        service.onSeated(order(45L, "Наталья", null, TableReservationStatus.SEATED, START, SEATED_AT));
        SendMessage second = sent();
        assertThat(second.getChatId()).isEqualTo("500");
        assertThat(second.getReplyToMessageId()).isNull();
    }

    @Test
    void theBookingsOwnStaffChatWinsOverTheDefaultOne() throws Exception {
        TableReservationOrder elsewhere = new TableReservationOrder(44L, CHAT, CHAT, null, 5L, "5", "Table 5", null, null,
                TableReservationStatus.SEATED, "TELEGRAM", START, START.plusSeconds(5400), 2, "Наталья", PHONE, null,
                876857557L, null, "600", null, START, START, SEATED_AT);

        service.onSeated(elsewhere);

        assertThat(sent().getChatId()).isEqualTo("600");
    }

    @Test
    void aSecondSeatedEventForTheSameBookingSendsNothing() throws Exception {
        when(hostessState.markGuestCardSent(44L)).thenReturn(true, false);

        service.onSeated(seated);
        service.onSeated(seated);

        verify(bot, times(1)).execute(any(SendMessage.class));
        verify(reservations, times(1)).findOrdersByChatId(anyLong(), anyInt());
    }

    @Test
    void aBrokenSourceOrMarkerStillGivesTheShortCardOnce() throws Exception {
        when(hostessState.markGuestCardSent(44L)).thenThrow(new IllegalStateException("redis down"));
        when(reservations.findOrdersByChatId(anyLong(), anyInt())).thenThrow(new IllegalStateException("db down"));
        when(bills.findByChatId(anyLong(), anyInt())).thenThrow(new IllegalStateException("db down"));
        when(directory.waiterOfTable(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        service.onSeated(seated);

        assertThat(sent().getText()).isEqualTo("""
                🧾 <b>Стол 5 (5) — гость сел</b> 14:32 · гостей: 2 · бронь #44
                <b>Гость:</b> Наталья · первый визит в боте
                <b>Аллергены:</b> не указаны""");
    }

    @Test
    void nothingLeavesWhileTelegramOrTheNotificationsAreOffOrTheChatIsUnknown() {
        service.setTelegramEnabled(false);
        service.onSeated(seated);

        service.setTelegramEnabled(true);
        service.setNotificationsEnabled(false);
        service.onSeated(seated);

        service.setNotificationsEnabled(true);
        service.setDefaultHostessChatId("");
        service.onSeated(seated);

        verifyNoInteractions(bot);
    }

    private SendMessage sent() throws Exception {
        ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(bot, org.mockito.Mockito.atLeastOnce()).execute(captor.capture());
        List<BotApiMethod> all = captor.getAllValues();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof SendMessage message) {
                return message;
            }
        }
        throw new AssertionError("Nothing was sent; sent: " + all);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TelegramBot> provider(TelegramBot bot) {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private static TableReservationOrder order(Long id, String guestName, String comment, TableReservationStatus status,
                                               Instant start, Instant seatedAt) {
        return new TableReservationOrder(id, CHAT, CHAT, null, 5L, "5", "Table 5", null, null, status, "TELEGRAM",
                start, start.plusSeconds(5400), 2, guestName, PHONE, comment, 876857557L, null, null, null,
                start.minusSeconds(86400), start.minusSeconds(86400), seatedAt);
    }

    private static GuestPreference preference(GuestPreferenceCategory category, String text) {
        return new GuestPreference(1L, CHAT, CHAT, null, "AERIS", category, text, "TELEGRAM", GuestPreferenceStatus.ACTIVE,
                1.0, "PREFERENCE", null, null, START, START);
    }

    private static GuestBill bill(Long id, Long reservationId) {
        return new GuestBill(id, CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "TELEGRAM", reservationId, null, null,
                GuestBillStatus.ESTIMATED, BillPayState.NOT_REPORTED, null, null, "RUB", null, null, null, null, START, START);
    }

    private static GuestBillLine line(Long billId, String title, int quantity, boolean included) {
        return new GuestBillLine(null, billId, 1, null, title, quantity, null, included);
    }

    private static VisitReview review(Long billId, int stars) {
        return new VisitReview(billId, billId, CHAT, START, stars, null, null, START, START, START);
    }
}
