package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.domain.booking.BookingHostessStateStorage.EditSession;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * «✏️ Изменить» on the hostess card (#150): the panel, its three steps, what each press applies, and how a stale
 * panel behaves. The booking is on Monday 12.10.2026 at 13:00–14:30 Yekaterinburg; the clock says 10:00 that morning.
 */
class HostessBookingEditServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final Instant START = Instant.parse("2026-10-12T08:00:00Z");
    private static final Instant END = Instant.parse("2026-10-12T09:30:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private final BookingStaffEditProperties properties = new BookingStaffEditProperties();
    private final TableReservationService reservations = mock(TableReservationService.class);
    private final BookingHostessStateStorage storage = mock(BookingHostessStateStorage.class);
    private final TelegramBot bot = mock(TelegramBot.class);
    private final TelegramSystemNotifier systemNotifier = mock(TelegramSystemNotifier.class);
    private final HostessBookingEditService service = new HostessBookingEditService(
            properties, reservations, storage, new VenueOpeningHours(), provider(bot), systemNotifier,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final VenueTable table5 = table(5L, "5", 4);
    private final VenueTable table7 = table(7L, "7", 6);
    private final TableReservationOrder order = order(44L, table5, TableReservationStatus.CONFIRMED, 2, START, END, null);

    @BeforeEach
    void theFeatureIsOnAndTheHostessChatIsKnown() throws Exception {
        properties.setEnabled(true);
        service.setHostessChatId("500");
        service.setTelegramEnabled(true);
        when(reservations.getReservation(44L)).thenReturn(order);
        when(reservations.listTables("AERIS")).thenReturn(List.of(table5, table7));
        Message panel = new Message();
        panel.setMessageId(321);
        when(bot.execute(any(SendMessage.class))).thenReturn(panel);
    }

    @Test
    void otherChatsAndOtherCallbacksAreNotHandled() {
        assertThat(service.handleCallback("table_booking:edit:44", 501L, 100, "@dasha").handled()).isFalse();
        assertThat(service.handleCallback("table_booking:confirm:44", 500L, 100, "@dasha").handled()).isFalse();
        assertThat(service.handleCallback("table_booking:edit:44:nonsense", 500L, 100, "@dasha").handled()).isFalse();
        assertThat(service.handleCallback(null, 500L, 100, "@dasha").handled()).isFalse();
    }

    @Test
    void withTheFlagOffAnOldButtonSaysSoAndChangesNothing() {
        properties.setEnabled(false);

        var result = service.handleCallback("table_booking:edit:44", 500L, 100, "@dasha");

        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).contains("выключена", "ASTOR_BOOKING_STAFF_EDIT_ENABLED");
        verify(reservations, never()).getReservation(any());
        verify(storage, never()).startEditSession(any(), any(), any());
    }

    @Test
    void theButtonOpensAPanelUnderTheCardAndStartsASession() throws Exception {
        when(storage.editSession(44L)).thenReturn(Optional.empty());

        var result = service.handleCallback("table_booking:edit:44", 500L, 100, "@dasha");

        assertThat(result.answerText()).isEqualTo("Правка брони #44 открыта");
        ArgumentCaptor<EditSession> session = ArgumentCaptor.forClass(EditSession.class);
        verify(storage).startEditSession(eq(44L), session.capture(), eq(TTL));
        assertThat(session.getValue().chatId()).isEqualTo("500");
        assertThat(session.getValue().messageId()).isEqualTo(321);
        assertThat(session.getValue().startedBy()).isEqualTo("@dasha");
        assertThat(session.getValue().startedAt()).isEqualTo(NOW);

        SendMessage panel = sentPanel();
        assertThat(panel.getChatId()).isEqualTo("500");
        assertThat(panel.getReplyToMessageId()).isEqualTo(100);
        assertThat(panel.getText()).contains("Правка брони #44", "Наталья", "Стол 5 (5)", "12.10.2026, 13:00 - 14:30", "гостей: 2",
                "подтверждена", "Что меняем?", "10 мин")
                .doesNotContain("Saby");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) panel.getReplyMarkup();
        assertThat(keyboard.getKeyboard().getFirst()).extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(
                        tuple("🪑 Стол", "table_booking:edit:44:table"),
                        tuple("🕒 Время", "table_booking:edit:44:time"),
                        tuple("👥 Гостей", "table_booking:edit:44:party"));
        assertThat(keyboard.getKeyboard().get(1)).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("table_booking:edit:44:cancel");
    }

    @Test
    void thePanelAsksForAManualPrestoUpdateWhenTheBookingIsAlreadyThere() throws Exception {
        TableReservationOrder inPresto = order(44L, table5, TableReservationStatus.CONFIRMED, 2, START, END, "saby-44");
        when(reservations.getReservation(44L)).thenReturn(inPresto);
        when(storage.editSession(44L)).thenReturn(Optional.empty());

        service.handleCallback("table_booking:edit:44", 500L, 100, "@dasha");

        assertThat(sentPanel().getText()).contains("Saby", "уже в Presto", "вручную");
    }

    @Test
    void aStepWithoutALivePanelSaysItExpiredAndChangesNothing() throws Exception {
        when(storage.editSession(44L)).thenReturn(Optional.empty());

        var result = service.handleCallback("table_booking:edit:44:party:inc", 500L, 321, "@dasha");

        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).contains("истекла", "«✏️ Изменить»");
        verify(reservations, never()).changeByStaff(any(), any(), any());
        verify(storage, never()).touchEditSession(any(), any());
        EditMessageReplyMarkup stripped = lastOf(EditMessageReplyMarkup.class);
        assertThat(stripped.getMessageId()).isEqualTo(321);
        assertThat(stripped.getReplyMarkup()).isNull();
    }

    @Test
    void aStepFromAnOlderPanelCountsAsExpiredToo() throws Exception {
        live();

        var result = service.handleCallback("table_booking:edit:44:table", 500L, 300, "@dasha");

        assertThat(result.answerText()).contains("истекла");
        assertThat(lastOf(EditMessageReplyMarkup.class).getMessageId()).isEqualTo(300);
        verify(reservations, never()).tablesFreeFor(any());
    }

    @Test
    void tableStepListsTheTablesFreeForTheSlotWithTheCurrentOneMarked() throws Exception {
        live();
        when(reservations.tablesFreeFor(order)).thenReturn(List.of(table5, table7));

        var result = service.handleCallback("table_booking:edit:44:table", 500L, 321, "@dasha");

        assertThat(result.answerText()).contains("выберите стол");
        verify(storage).touchEditSession(44L, TTL);
        EditMessageText panel = lastOf(EditMessageText.class);
        assertThat(panel.getMessageId()).isEqualTo(321);
        assertThat(panel.getText()).contains("Стол</b> на 13:00 - 14:30 для 2 гостей");
        List<List<InlineKeyboardButton>> rows = panel.getReplyMarkup().getKeyboard();
        assertThat(rows.getFirst()).extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(tuple("• Стол 5", "table_booking:edit:44:table:5"), tuple("Стол 7", "table_booking:edit:44:table:7"));
        assertThat(rows.getLast()).extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(tuple("← Назад", "table_booking:edit:44:menu"));
    }

    @Test
    void choosingATableAppliesItAtOnceAndTheGuestIsNotTold() throws Exception {
        live();
        TableReservationOrder moved = order(44L, table7, TableReservationStatus.CONFIRMED, 2, START, END, null);
        when(reservations.changeByStaff(eq(44L), any(), eq("@dasha"))).thenReturn(moved);

        var result = service.handleCallback("table_booking:edit:44:table:7", 500L, 321, "@dasha");

        assertThat(result.answerText()).isEqualTo("Бронь #44: стол 5 → 7");
        ArgumentCaptor<TableReservationChangeCommand> command = ArgumentCaptor.forClass(TableReservationChangeCommand.class);
        verify(reservations).changeByStaff(eq(44L), command.capture(), eq("@dasha"));
        assertThat(command.getValue().tableCode()).isEqualTo("7");
        assertThat(command.getValue().requestedStartAt()).isNull();
        assertThat(command.getValue().partySize()).isNull();
        verify(storage).appendEditLog(eq(44L), contains("@dasha — стол 5 → 7"), eq(TTL));
        verify(systemNotifier).sendBookingStaffChange("@dasha", 44L, "стол 5 → 7");
        EditMessageText panel = lastOf(EditMessageText.class);
        assertThat(panel.getText()).contains("Готово: стол 5 → 7", "Гостю не сообщаем", "Стол 7 (7)", "Изменения", "стол 5 → 7", "Что меняем?");
        assertThat(panel.getReplyMarkup().getKeyboard().getFirst()).extracting(InlineKeyboardButton::getText)
                .containsExactly("🪑 Стол", "🕒 Время", "👥 Гостей");
    }

    @Test
    void aTableTheServiceRefusesIsExplainedAndTheListIsShownAgain() throws Exception {
        live();
        when(reservations.tablesFreeFor(order)).thenReturn(List.of(table5, table7));
        when(reservations.changeByStaff(eq(44L), any(), eq("@dasha")))
                .thenThrow(refusal("Table capacity is lower than requested party size", "3"));

        var result = service.handleCallback("table_booking:edit:44:table:3", 500L, 321, "@dasha");

        assertThat(result.answerText()).isEqualTo("Стол 3 вмещает меньше гостей — выберите другой стол или число гостей");
        EditMessageText panel = lastOf(EditMessageText.class);
        assertThat(panel.getText()).contains("⚠️ Стол 3 вмещает меньше гостей", "нажмите нужный");
        assertThat(panel.getReplyMarkup().getKeyboard().getFirst()).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("table_booking:edit:44:table:5", "table_booking:edit:44:table:7");
        verify(storage, never()).appendEditLog(any(), any(), any());
        verify(systemNotifier, never()).sendBookingStaffChange(any(), any(), any());
    }

    @Test
    void timeStepOffersTheSlotsOfThatEveningThatHaveNotPassed() throws Exception {
        List<Instant> slots = service.slots(order);

        // Monday: 12:00–02:00 Yekaterinburg, half-hour steps, the late hours belong to the same evening.
        assertThat(slots).hasSize(28);
        assertThat(slots.getFirst()).isEqualTo(Instant.parse("2026-10-12T07:00:00Z"));
        assertThat(slots.getLast()).isEqualTo(Instant.parse("2026-10-12T20:30:00Z"));
        assertThat(slots).contains(START);

        HostessBookingEditService later = new HostessBookingEditService(properties, reservations, storage, new VenueOpeningHours(),
                provider(bot), systemNotifier, Clock.fixed(Instant.parse("2026-10-12T08:15:00Z"), ZoneOffset.UTC));
        assertThat(later.slots(order).getFirst()).isEqualTo(Instant.parse("2026-10-12T08:30:00Z"));

        live();
        service.handleCallback("table_booking:edit:44:time", 500L, 321, "@dasha");
        EditMessageText panel = lastOf(EditMessageText.class);
        assertThat(panel.getText()).contains("Время</b> на 12.10.2026, визит 1 ч 30 мин");
        List<List<InlineKeyboardButton>> rows = panel.getReplyMarkup().getKeyboard();
        assertThat(rows.getFirst()).extracting(InlineKeyboardButton::getText).containsExactly("12:00", "12:30", "• 13:00", "13:30");
        assertThat(rows.getFirst().get(2).getCallbackData()).isEqualTo("table_booking:edit:44:time:202610121300");
        assertThat(rows.getLast().getFirst().getCallbackData()).isEqualTo("table_booking:edit:44:menu");
    }

    @Test
    void choosingATimeMovesTheBookingOnTheSameTableAndTheGuestHearsAboutIt() throws Exception {
        live();
        Instant newStart = Instant.parse("2026-10-12T09:30:00Z");
        TableReservationOrder moved = order(44L, table5, TableReservationStatus.CONFIRMED, 2, newStart, newStart.plus(Duration.ofMinutes(90)), null);
        when(reservations.changeByStaff(eq(44L), any(), eq("@dasha"))).thenReturn(moved);

        var result = service.handleCallback("table_booking:edit:44:time:202610121430", 500L, 321, "@dasha");

        assertThat(result.answerText()).isEqualTo("Бронь #44: время 13:00 → 14:30");
        ArgumentCaptor<TableReservationChangeCommand> command = ArgumentCaptor.forClass(TableReservationChangeCommand.class);
        verify(reservations).changeByStaff(eq(44L), command.capture(), eq("@dasha"));
        assertThat(command.getValue().tableCode()).isEqualTo("5");
        assertThat(command.getValue().requestedStartAt()).isEqualTo(newStart);
        assertThat(command.getValue().requestedEndAt()).isEqualTo(Instant.parse("2026-10-12T11:00:00Z"));
        assertThat(command.getValue().partySize()).isNull();
        assertThat(lastOf(EditMessageText.class).getText()).contains("Готово: время 13:00 → 14:30", "Гостю отправлено сообщение о переносе");
    }

    @Test
    void aSlotThatAlreadyPassedIsRefused() throws Exception {
        live();

        var result = service.handleCallback("table_booking:edit:44:time:202610120900", 500L, 321, "@dasha");

        assertThat(result.answerText()).isEqualTo("Это время уже прошло");
        verify(reservations, never()).changeByStaff(any(), any(), any());
        assertThat(lastOf(EditMessageText.class).getText()).contains("⚠️ Это время уже прошло");
    }

    @Test
    void partyStepOffersMinusPlusAndTheNumbers() throws Exception {
        live();

        service.handleCallback("table_booking:edit:44:party", 500L, 321, "@dasha");

        EditMessageText panel = lastOf(EditMessageText.class);
        assertThat(panel.getText()).contains("Гостей</b> сейчас: 2 (Стол 5, до 4 мест)");
        List<List<InlineKeyboardButton>> rows = panel.getReplyMarkup().getKeyboard();
        assertThat(rows.getFirst()).extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(
                        tuple("−1", "table_booking:edit:44:party:dec"),
                        tuple("👥 2", "table_booking:edit:44:party"),
                        tuple("+1", "table_booking:edit:44:party:inc"));
        assertThat(rows.get(1)).extracting(InlineKeyboardButton::getText).containsExactly("1", "• 2", "3", "4");
        assertThat(rows.get(1).get(3).getCallbackData()).isEqualTo("table_booking:edit:44:party:4");
        assertThat(rows.get(3)).extracting(InlineKeyboardButton::getText).containsExactly("9", "10", "11", "12");
        assertThat(rows.getLast().getFirst().getText()).isEqualTo("← Назад");
    }

    @Test
    void plusOneAppliesTheNewPartySizeOnTheSameTableSilently() throws Exception {
        live();
        TableReservationOrder bigger = order(44L, table5, TableReservationStatus.CONFIRMED, 3, START, END, null);
        when(reservations.changeByStaff(eq(44L), any(), eq("@dasha"))).thenReturn(bigger);

        var result = service.handleCallback("table_booking:edit:44:party:inc", 500L, 321, "@dasha");

        assertThat(result.answerText()).isEqualTo("Бронь #44: гостей 2 → 3");
        ArgumentCaptor<TableReservationChangeCommand> command = ArgumentCaptor.forClass(TableReservationChangeCommand.class);
        verify(reservations).changeByStaff(eq(44L), command.capture(), eq("@dasha"));
        assertThat(command.getValue().partySize()).isEqualTo(3);
        assertThat(command.getValue().tableCode()).isEqualTo("5");
        assertThat(command.getValue().requestedStartAt()).isNull();
        assertThat(lastOf(EditMessageText.class).getText()).contains("гостей: 3", "Гостю не сообщаем");

        when(reservations.changeByStaff(eq(44L), any(), eq("@dasha")))
                .thenReturn(order(44L, table5, TableReservationStatus.CONFIRMED, 4, START, END, null));
        assertThat(service.handleCallback("table_booking:edit:44:party:4", 500L, 321, "@dasha").answerText())
                .isEqualTo("Бронь #44: гостей 2 → 4");
    }

    @Test
    void partySizeOutOfRangeIsRefusedWithoutAsking() {
        live();
        TableReservationOrder alone = order(44L, table5, TableReservationStatus.CONFIRMED, 1, START, END, null);
        when(reservations.getReservation(44L)).thenReturn(alone);

        assertThat(service.handleCallback("table_booking:edit:44:party:dec", 500L, 321, "@dasha").answerText())
                .contains("Меньше одного");
        assertThat(service.handleCallback("table_booking:edit:44:party:13", 500L, 321, "@dasha").answerText())
                .contains("Больше 12 гостей");
        assertThat(service.handleCallback("table_booking:edit:44:party:1", 500L, 321, "@dasha").answerText())
                .contains("что меняем?");
        verify(reservations, never()).changeByStaff(any(), any(), any());
    }

    @Test
    void aPartySizeTheTableCannotTakeIsExplainedAndTheKeyboardIsShownAgain() throws Exception {
        live();
        when(reservations.changeByStaff(eq(44L), any(), eq("@dasha")))
                .thenThrow(refusal("Table capacity is lower than requested party size", "5"));

        var result = service.handleCallback("table_booking:edit:44:party:5", 500L, 321, "@dasha");

        assertThat(result.answerText()).startsWith("Стол 5 вмещает меньше гостей");
        EditMessageText panel = lastOf(EditMessageText.class);
        assertThat(panel.getText()).contains("⚠️ Стол 5 вмещает меньше гостей", "нажмите −1 / +1 или число");
        assertThat(panel.getReplyMarkup().getKeyboard().getFirst()).extracting(InlineKeyboardButton::getText)
                .containsExactly("−1", "👥 2", "+1");
    }

    @Test
    void closingEndsTheSessionAndLeavesTheJournalWithoutButtons() throws Exception {
        when(storage.editSession(44L)).thenReturn(Optional.of(new EditSession("500", 321, "@dasha", NOW, "13:05 @dasha — стол 5 → 7")));

        var result = service.handleCallback("table_booking:edit:44:cancel", 500L, 321, "@dasha");

        assertThat(result.answerText()).isEqualTo("Правка брони #44 закрыта");
        verify(storage).endEditSession(44L);
        EditMessageText panel = lastOf(EditMessageText.class);
        assertThat(panel.getText()).contains("Правка закрыта", "стол 5 → 7");
        assertThat(panel.getReplyMarkup()).isNull();
    }

    @Test
    void aBookingThatIsNoLongerActiveCannotBeEdited() throws Exception {
        when(reservations.getReservation(44L)).thenReturn(order(44L, table5, TableReservationStatus.CANCELLED, 2, START, END, null));

        assertThat(service.handleCallback("table_booking:edit:44", 500L, 100, "@dasha").answerText())
                .isEqualTo("Бронь #44 уже отменена — менять нечего");
        verify(storage, never()).startEditSession(any(), any(), any());

        assertThat(service.handleCallback("table_booking:edit:44:table", 500L, 321, "@dasha").answerText()).contains("менять нечего");
        verify(storage).endEditSession(44L);
        assertThat(lastOf(EditMessageReplyMarkup.class).getMessageId()).isEqualTo(321);
    }

    @Test
    void whoPressedIsNamedByUsernameThenNameThenId() {
        assertThat(HostessBookingEditService.staffRef("dasha", "Даша", 42L)).isEqualTo("@dasha");
        assertThat(HostessBookingEditService.staffRef(" ", "Даша", 42L)).isEqualTo("Даша (id 42)");
        assertThat(HostessBookingEditService.staffRef(null, null, 42L)).isEqualTo("id 42");
        assertThat(HostessBookingEditService.staffRef(null, null, null)).isEqualTo("неизвестно");
        assertThat(HostessBookingEditService.humanDuration(Duration.ofMinutes(90))).isEqualTo("1 ч 30 мин");
        assertThat(HostessBookingEditService.humanDuration(Duration.ofHours(2))).isEqualTo("2 ч");
        assertThat(HostessBookingEditService.humanDuration(Duration.ofMinutes(45))).isEqualTo("45 мин");
    }

    @Test
    void refusalsAreExplainedInTheHostessWords() {
        assertThat(HostessBookingEditService.humanError(refusal("Table already has an active hold for this time window", "7"), null))
                .isEqualTo("Стол 7 занят на это время другой бронью");
        assertThat(HostessBookingEditService.humanError(refusal("Table is busy in the restaurant booking system", "7"), null))
                .isEqualTo("Стол 7 занят на это время в Presto");
        assertThat(HostessBookingEditService.humanError(refusal("Table is not bookable", "7"), null))
                .isEqualTo("Стол 7 сейчас не бронируется");
        assertThat(HostessBookingEditService.humanError(
                new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "requestedEndAt must be after requestedStartAt"), "5"))
                .isEqualTo("Время указано неверно");
        assertThat(HostessBookingEditService.humanError(
                new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Only active table reservations can be changed"), "5"))
                .isEqualTo("Бронь уже не активна");
    }

    // ---- helpers ----

    private void live() {
        when(storage.editSession(44L)).thenReturn(Optional.of(new EditSession("500", 321, "@dasha", NOW, "")));
    }

    private SendMessage sentPanel() throws Exception {
        return lastOf(SendMessage.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends BotApiMethod<?>> T lastOf(Class<T> type) throws Exception {
        ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(bot, atLeastOnce()).execute(captor.capture());
        List<BotApiMethod> all = captor.getAllValues();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (type.isInstance(all.get(i))) {
                return type.cast(all.get(i));
            }
        }
        throw new AssertionError("Nothing of " + type.getSimpleName() + " was sent; sent: " + all);
    }

    private static ApiException refusal(String message, String tableCode) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, message, Map.of("tableCode", tableCode));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TelegramBot> provider(TelegramBot bot) {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private static VenueTable table(Long id, String code, int capacity) {
        return new VenueTable(id, "AERIS", code, "Table " + code, "MAIN_HALL", 1, capacity, null, true, true, 2, "AERIS PLAN",
                Integer.parseInt(code), NOW, NOW);
    }

    private static TableReservationOrder order(Long id, VenueTable table, TableReservationStatus status, int partySize,
                                               Instant start, Instant end, String externalId) {
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, table.id(), table.tableCode(), table.displayName(),
                null, null, status, "TELEGRAM", start, end, partySize, "Наталья", "+79990000000", null, 876857557L, null, "500",
                externalId, NOW, NOW);
    }
}
