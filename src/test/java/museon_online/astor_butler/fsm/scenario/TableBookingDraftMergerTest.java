package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.fsm.core.BotState;
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
 * "32.13" answered "Произошла ошибка", and so did "19.30" locally. Nothing a guest types may crash the dialogue.
 */
@ExtendWith(MockitoExtension.class)
class TableBookingDraftMergerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @Mock
    private TableBookingDraftStorage draftStorage;

    private TableBookingDraftMerger merger;

    @BeforeEach
    void setUp() {
        BookingTimeProvider timeProvider = new BookingTimeProvider(Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), BookingTimeProvider.VENUE_ZONE));
        merger = new TableBookingDraftMerger(draftStorage, timeProvider);
        ReflectionTestUtils.setField(merger, "defaultVenueCode", "AERIS");
    }

    private TableBookingDraftStorage.Draft answer(BotState state, String text, LocalDate storedDate) {
        TableBookingDraftStorage.Draft stored = new TableBookingDraftStorage.Draft("AERIS", null, null, storedDate, null, 2,
                null, null, null, true, "Забронировать стол");
        lenient().when(draftStorage.find(any())).thenReturn(Optional.of(stored));
        IncomingMessage incoming = IncomingMessage.telegram(1773317437L, 1773317437L, 356, 284069928, text, null,
                "Наталья", "Поединенко", "Poedinenko", "ru", false, "284069928");
        return merger.merge(incoming, state, text.toLowerCase().trim(), null);
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
}
