package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * What a guest types for the day and the time, as it reached the production bot on 2026-10-05:
 * "32.13" answered "Произошла ошибка", and so did "19.30" locally. Nothing a guest types may crash the dialogue,
 * and a time the bot accepts has to be the time the guest meant.
 */
@ExtendWith(MockitoExtension.class)
class TableBookingDraftMergerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @Mock
    private TableBookingDraftStorage draftStorage;

    private final GuestInputUnderstandingService understanding = new GuestInputUnderstandingService();

    private TableBookingDraftMerger merger;

    @BeforeEach
    void setUp() {
        BookingTimeProvider timeProvider = new BookingTimeProvider(Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), BookingTimeProvider.VENUE_ZONE));
        merger = new TableBookingDraftMerger(draftStorage, timeProvider);
        ReflectionTestUtils.setField(merger, "defaultVenueCode", "AERIS");
    }

    private TableBookingDraftStorage.Draft answer(BotState state, String text, LocalDate storedDate) {
        return merger.merge(guestSays(text, storedDate), state, text.toLowerCase().trim(), null);
    }

    /** The same answer the way the bot hears it: the understanding service first, then the merger. */
    private TableBookingDraftStorage.Draft heard(BotState state, String text, LocalDate storedDate) {
        UnderstoodInput understood = understanding.understand(text, state);
        return merger.merge(guestSays(text, storedDate), state, understood.routeText(), understood);
    }

    /** The party size the bot takes from an answer to "how many guests", when nothing is known yet. */
    private Integer partyHeard(String text) {
        UnderstoodInput understood = understanding.understand(text, BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE);
        return merger.merge(firstAnswer(text), BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, understood.routeText(), understood).partySize();
    }

    private Integer partyByMergerAlone(String text) {
        return merger.merge(firstAnswer(text), BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, text.toLowerCase().trim(), null).partySize();
    }

    private IncomingMessage firstAnswer(String text) {
        lenient().when(draftStorage.find(any())).thenReturn(Optional.empty());
        return IncomingMessage.telegram(1773317437L, 1773317437L, 356, 284069928, text, null,
                "Наталья", "Поединенко", "Poedinenko", "ru", false, "284069928");
    }

    private IncomingMessage guestSays(String text, LocalDate storedDate) {
        TableBookingDraftStorage.Draft stored = new TableBookingDraftStorage.Draft("AERIS", null, null, storedDate, null, 2,
                null, null, null, true, "Забронировать стол");
        lenient().when(draftStorage.find(any())).thenReturn(Optional.of(stored));
        return IncomingMessage.telegram(1773317437L, 1773317437L, 356, 284069928, text, null,
                "Наталья", "Поединенко", "Poedinenko", "ru", false, "284069928");
    }

    @Test
    void aDayThatDoesNotExistIsNotADateAndDoesNotCrash() {
        for (String text : new String[]{"32.13", "31.02", "00.00", "45/45", "29.02", "2026-13-45"}) {
            TableBookingDraftStorage.Draft draft = answer(BotState.TABLE_BOOKING_COLLECT_DATE, text, null);

            assertThat(draft.requestedDate()).as(text).isNull();
            assertThat(draft.requestedTime()).as(text).isNull();
        }
    }

    @Test
    void aRealDayStaysADate() {
        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_DATE, "15.10", null).requestedDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_DATE, "15.10.2026", null).requestedDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_DATE, "29.02.2028", null).requestedDate()).isEqualTo(LocalDate.of(2028, 2, 29));
        // A day that has already passed this year means the same day next year, as before.
        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_DATE, "01.03", null).requestedDate()).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_DATE, "завтра", null).requestedDate()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void aTimeWrittenWithADotIsATimeAndKeepsTheChosenDay() {
        LocalDate chosen = TODAY.plusDays(1);
        String[][] cases = {{"19.30", "19:30"}, {"20.00", "20:00"}, {"18.15", "18:15"}, {"21.45", "21:45"}, {"в 19.30", "19:30"}, {"7.30 вечера", "19:30"}};
        for (String[] example : cases) {
            TableBookingDraftStorage.Draft draft = answer(BotState.TABLE_BOOKING_COLLECT_TIME, example[0], chosen);

            assertThat(draft.requestedTime()).as(example[0]).isEqualTo(LocalTime.parse(example[1]));
            assertThat(draft.requestedDate()).as(example[0]).isEqualTo(chosen);
        }
    }

    @Test
    void aTimeWithADotIsNotMistakenForATimeWhileTheBotAsksForTheDay() {
        TableBookingDraftStorage.Draft draft = answer(BotState.TABLE_BOOKING_COLLECT_DATE, "19.30", null);

        assertThat(draft.requestedDate()).isNull();
        assertThat(draft.requestedTime()).isNull();
    }

    @Test
    void oneMessageWithTheDayAndADottedTimeGivesBoth() {
        TableBookingDraftStorage.Draft tomorrow = answer(BotState.TABLE_BOOKING_INTENT, "завтра в 19.30", null);
        assertThat(tomorrow.requestedDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(tomorrow.requestedTime()).isEqualTo(LocalTime.of(19, 30));

        TableBookingDraftStorage.Draft explicit = answer(BotState.TABLE_BOOKING_INTENT, "на 15.10 в 19.30", null);
        assertThat(explicit.requestedDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(explicit.requestedTime()).isEqualTo(LocalTime.of(19, 30));
    }

    @Test
    void aTimeRangeDoesNotCrashAndStartsAtItsFirstTime() {
        LocalDate chosen = TODAY.plusDays(1);

        TableBookingDraftStorage.Draft draft = answer(BotState.TABLE_BOOKING_COLLECT_TIME, "19:00-21:00", chosen);

        assertThat(draft.requestedDate()).isEqualTo(chosen);
        assertThat(draft.requestedTime()).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    void anOrdinaryTimeStillWorks() {
        LocalDate chosen = TODAY.plusDays(1);

        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_TIME, "19:00", chosen).requestedTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_TIME, "семь вечера", chosen).requestedTime()).isNull();
        assertThat(answer(BotState.TABLE_BOOKING_COLLECT_TIME, "в 7 вечера", chosen).requestedTime()).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    void theWordAfterTheHourSaysWhichPartOfTheDayItIs() {
        LocalDate chosen = TODAY.plusDays(1);
        String[][] cases = {
                {"3 ночи", "03:00"}, {"в 2 ночи", "02:00"}, {"12 ночи", "00:00"}, {"в 11 ночи", "23:00"},
                {"в 3 часа ночи", "03:00"}, {"в 11 часов ночи", "23:00"},
                {"в 7 вечера", "19:00"}, {"в 7 часов вечера", "19:00"}, {"7:30 вечера", "19:30"},
                {"в 2 дня", "14:00"}, {"в 2 часа дня", "14:00"}, {"в 12 дня", "12:00"}, {"в 11 дня", "11:00"},
                {"в 9 утра", "09:00"}, {"в 9 часов утра", "09:00"}};
        for (String[] example : cases) {
            LocalTime expected = LocalTime.parse(example[1]);

            assertThat(heard(BotState.TABLE_BOOKING_COLLECT_TIME, example[0], chosen).requestedTime()).as(example[0]).isEqualTo(expected);
            assertThat(answer(BotState.TABLE_BOOKING_COLLECT_TIME, example[0], chosen).requestedTime()).as(example[0] + ", merger alone").isEqualTo(expected);
        }
    }

    @Test
    void aGreetingWithTheWordEveningDoesNotMoveAnExactTime() {
        TableBookingDraftStorage.Draft draft = heard(BotState.TABLE_BOOKING_INTENT, "Добрый вечер! Столик завтра на 11:30", null);

        assertThat(draft.requestedDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(draft.requestedTime()).isEqualTo(LocalTime.of(11, 30));
    }

    @Test
    void adultsAndChildrenAreCountedTogether() {
        String[][] cases = {
                {"двое взрослых и ребёнок", "3"}, {"Двое взрослых и ребенок", "3"}, {"2 взрослых и 1 ребенок", "3"},
                {"двое взрослых и двое детей", "4"}, {"трое взрослых, двое детей", "5"}, {"2 взрослых + 2 детей", "4"},
                {"один взрослый и ребенок", "2"}, {"нас будет двое взрослых с ребёнком", "3"}};
        for (String[] example : cases) {
            Integer expected = Integer.valueOf(example[1]);

            assertThat(partyHeard(example[0])).as(example[0]).isEqualTo(expected);
            assertThat(partyByMergerAlone(example[0])).as(example[0] + ", merger alone").isEqualTo(expected);
        }
    }

    @Test
    void aPartyWithoutChildrenIsCountedAsBefore() {
        assertThat(partyHeard("на двоих")).isEqualTo(2);
        assertThat(partyHeard("двое взрослых")).isEqualTo(2);
        assertThat(partyHeard("4")).isEqualTo(4);
        // How many children is not said, so the number of adults is not passed off as the whole party.
        assertThat(partyHeard("двое взрослых и дети")).isNull();
        assertThat(partyByMergerAlone("двое взрослых и дети")).isNull();
    }

    @Test
    void aClockTimeThatDoesNotExistIsNotATime() {
        LocalDate chosen = TODAY.plusDays(1);
        // "24:00" used to become 00:00 of the chosen day, which is a whole day earlier than the guest means.
        for (String text : new String[]{"25:00", "30:00", "19:75", "24:00", "24:30", "99:99"}) {
            assertThat(heard(BotState.TABLE_BOOKING_COLLECT_TIME, text, chosen).requestedTime()).as(text).isNull();
            assertThat(answer(BotState.TABLE_BOOKING_COLLECT_TIME, text, chosen).requestedTime()).as(text + ", merger alone").isNull();
        }
    }

    /** A whole request in one message, the way the bot hears it: nothing is known before, the understanding service goes first. */
    private TableBookingDraftStorage.Draft askedAtOnce(String text) {
        UnderstoodInput understood = understanding.understand(text, BotState.READY_FOR_DIALOG);
        return merger.merge(withNothingKnown(text), BotState.READY_FOR_DIALOG, understood.routeText(), understood);
    }

    private TableBookingDraftStorage.Draft answeredAtTheTableStep(String text) {
        UnderstoodInput understood = understanding.understand(text, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION);
        return merger.merge(withNothingKnown(text), BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, understood.routeText(), understood);
    }

    private IncomingMessage withNothingKnown(String text) {
        lenient().when(draftStorage.find(any())).thenReturn(Optional.empty());
        return IncomingMessage.telegram(1773317437L, 1773317437L, 356, 284069928, text, null,
                "Наталья", "Поединенко", "Poedinenko", "ru", false, "284069928");
    }

    @Test
    void thePartySizeIsNotTakenForATableNumber() {
        // The understanding service turns "на двоих" into "на 2 гостей"; that 2 is the party, not table number 2.
        for (String text : new String[]{"Забронируй стол на двоих завтра в 19:00", "Хочу столик на троих завтра в 20:00",
                "стол на 4 гостей завтра в 19:00", "нужен стол на 6 человек в пятницу в 20:00"}) {
            TableBookingDraftStorage.Draft draft = askedAtOnce(text);

            assertThat(draft.tableCode()).as(text).isNull();
            assertThat(draft.partySize()).as(text).isNotNull();
        }
        assertThat(askedAtOnce("Забронируй стол на двоих завтра в 19:00 у окна").tableCode()).isNull();
        assertThat(askedAtOnce("Забронируй стол на двоих завтра в 19:00 у окна").preferredZone()).isEqualTo("WINDOW");
    }

    @Test
    void aTableTheGuestNamesIsStillTaken() {
        assertThat(askedAtOnce("стол 5 на двоих завтра в 19:00").tableCode()).isEqualTo("5");
        assertThat(askedAtOnce("столик номер 7 на двоих завтра в 19:00").tableCode()).isEqualTo("7");
        assertThat(askedAtOnce("4 стол у окна на двоих завтра в 19:00").tableCode()).isEqualTo("4");
        assertThat(answeredAtTheTableStep("5").tableCode()).isEqualTo("5");
        assertThat(answeredAtTheTableStep("стол 12").tableCode()).isEqualTo("12");
        assertThat(answeredAtTheTableStep("4 стол у окна").tableCode()).isEqualTo("4");
    }

    @Test
    void theHourIsNotTakenFromThePartySizeOrTheTable() {
        String[][] cases = {
                {"Забронируй стол на двоих в понедельник в 9 утра", "09:00"}, {"стол на троих завтра в 2 дня", "14:00"},
                {"стол на 4 гостей завтра в 11 утра", "11:00"}, {"нужен стол на 6 человек в пятницу в 8 вечера", "20:00"},
                {"стол 5 на двоих завтра в 19:00", "19:00"}, {"стол на двоих завтра в 19:00", "19:00"}};
        for (String[] example : cases) {
            assertThat(askedAtOnce(example[0]).requestedTime()).as(example[0]).isEqualTo(LocalTime.parse(example[1]));
        }
        // The same without the understanding service: the merger alone reads the digits.
        assertThat(answer(BotState.TABLE_BOOKING_INTENT, "стол на 3 гостей завтра в 2 дня", null).requestedTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(answer(BotState.TABLE_BOOKING_INTENT, "стол на 2 гостей завтра в 9 утра", null).requestedTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void anHourSaidAsAnHourIsHeardNextToATable() {
        String[][] cases = {
                {"стол 5 на двоих завтра в 20", "5", "20:00"}, {"Стол 5 на двоих завтра к 8 вечера", "5", "20:00"},
                {"4 стол у окна завтра в 19", "4", "19:00"}, {"столик номер 7 завтра в 21 час", "7", "21:00"}};
        for (String[] example : cases) {
            TableBookingDraftStorage.Draft draft = askedAtOnce(example[0]);

            assertThat(draft.tableCode()).as(example[0]).isEqualTo(example[1]);
            assertThat(draft.requestedTime()).as(example[0]).isEqualTo(LocalTime.parse(example[2]));
        }
        assertThat(askedAtOnce("Забронируй стол на двоих завтра в 21 у окна").requestedTime()).isEqualTo(LocalTime.of(21, 0));
        assertThat(askedAtOnce("Забронируй стол на двоих завтра в 21 у окна").preferredZone()).isEqualTo("WINDOW");
    }

    @Test
    void aLoneNumberNextToATableIsStillNotTheHour() {
        // At the table step these are answers about the table and nothing else.
        for (String reply : new String[]{"5", "стол 12", "4 стол у окна", "стол 5", "у окна 7", "столик 9 в углу"}) {
            assertThat(answeredAtTheTableStep(reply).requestedTime()).as(reply).isNull();
        }
        assertThat(askedAtOnce("стол 12 на двоих завтра").requestedTime()).isNull();
    }
}
