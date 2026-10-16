package museon_online.astor_butler.domain.roster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading the vision model's answer: the recorded answer to the fixture picture, and the formats models drift into. */
class StaffScheduleParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16); // a Friday

    static String fixtureAnswer() {
        try (InputStream input = StaffScheduleParserTest.class.getResourceAsStream("/roster/schedule-week.answer.txt")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theRecordedAnswerToTheFixturePictureBecomesDaysNamesRolesAndHours() {
        StaffScheduleParser.Result result = StaffScheduleParser.parse(fixtureAnswer(), TODAY);

        assertThat(result.unreadable()).isEqualTo(1);
        // The bartender twice (another role) and Вера's day off on Saturday.
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.firstDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(result.lastDate()).isEqualTo(LocalDate.of(2026, 10, 18)); // «Вс» of this week
        assertThat(result.shifts()).hasSize(15);
        assertThat(result.shifts()).extracting(StaffScheduleParser.Shift::name).doesNotContain("Кирилл");

        List<StaffScheduleParser.Shift> friday = result.shifts().stream().filter(s -> s.date().equals(TODAY)).toList();
        assertThat(friday).extracting(StaffScheduleParser.Shift::name)
                .containsExactly("Алиса Т.", "Борис Примеров", "Вера Образцова", "Глеп Макетов", "Егор Пробный", "Пётр Неизвестный");
        assertThat(friday).extracting(StaffScheduleParser.Shift::role)
                .containsExactly(StaffRole.HOSTESS, StaffRole.MANAGER, StaffRole.WAITER, StaffRole.WAITER, StaffRole.WAITER, StaffRole.WAITER);
        assertThat(shift(result, TODAY, "Вера Образцова").start()).isEqualTo(LocalTime.of(10, 0));
        assertThat(shift(result, TODAY, "Вера Образцова").end()).isEqualTo(LocalTime.of(22, 0));
        assertThat(shift(result, TODAY, "Глеп Макетов").end()).isEqualTo(LocalTime.MIDNIGHT);
        // «11» in a cell is the length of the shift, not its start: the hours are unknown.
        assertThat(shift(result, TODAY, "Егор Пробный").start()).isNull();
        assertThat(shift(result, TODAY, "Егор Пробный").end()).isNull();
        assertThat(shift(result, TODAY.plusDays(1), "Борис Примеров").end()).isEqualTo(LocalTime.of(2, 0));
    }

    @Test
    void aPhoneInTheNameCellNeverSurvives() {
        StaffScheduleParser.Result result = StaffScheduleParser.parse(fixtureAnswer(), TODAY);

        assertThat(result.shifts()).extracting(StaffScheduleParser.Shift::name).contains("Олеся")
                .allSatisfy(name -> assertThat(name).doesNotContainPattern("\\d"));
        assertThat(StaffScheduleParser.name("Иван 8 (912) 345-67-89")).isEqualTo("Иван");
        assertThat(StaffScheduleParser.name("+79123456789")).isNull();
        assertThat(StaffScheduleParser.name("  Анна-Мария  ")).isEqualTo("Анна-Мария");
        assertThat(StaffScheduleParser.name("А".repeat(80))).hasSize(StaffScheduleParser.MAX_NAME_LENGTH);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "10:00-22:00  | 10:00 | 22:00",
            "10-22        | 10:00 | 22:00",
            "с 10 до 22   | 10:00 | 22:00",
            "9.30–18      | 09:30 | 18:00",
            "10:00 — 23:30| 10:00 | 23:30",
            "18:00-02:00  | 18:00 | 02:00",
            "12-24        | 12:00 | 00:00",
            "с 12         | 12:00 | null",
            "12:00        | 12:00 | null",
            "11           | null  | null",
            "до закрытия  | null  | null",
            "''           | null  | null",
            "25-30        | null  | null",
    })
    void hoursInTheShapesSchedulesUse(String text, String start, String end) {
        LocalTime[] hours = StaffScheduleParser.hours(text);

        assertThat(hours).isNotNull();
        assertThat(hours[0]).isEqualTo(start == null ? null : LocalTime.parse(start));
        assertThat(hours[1]).isEqualTo(end == null ? null : LocalTime.parse(end));
    }

    @ParameterizedTest
    @CsvSource({"В", "вых", "Выходной", "отпуск", "-", "x"})
    void aDayOffIsNoShift(String text) {
        assertThat(StaffScheduleParser.hours(text)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "2026-10-16 | 2026-10-16",
            "16.10      | 2026-10-16",
            "16/10      | 2026-10-16",
            "16.10.2026 | 2026-10-16",
            "16.10.26   | 2026-10-16",
            "Пн         | 2026-10-12",
            "воскресенье| 2026-10-18",
            "05.01      | 2027-01-05",
            "31.02      | null",
            "завтра?    | null",
    })
    void datesWithAndWithoutTheYear(String text, String expected) {
        assertThat(StaffScheduleParser.date(text, TODAY)).isEqualTo(expected == null ? null : LocalDate.parse(expected));
    }

    @Test
    void anAnswerWithNothingUsableIsEmptyNotAnError() {
        assertThat(StaffScheduleParser.parse(null, TODAY).empty()).isTrue();
        assertThat(StaffScheduleParser.parse("Не могу прочитать изображение.", TODAY).empty()).isTrue();
        assertThat(StaffScheduleParser.parse("{\"days\": [", TODAY).empty()).isTrue();
        assertThat(StaffScheduleParser.parse("{\"days\": []}", TODAY).empty()).isTrue();
        assertThat(StaffScheduleParser.parse("{\"days\": [{\"date\": \"когда-то\", \"staff\": [{\"name\": \"Анна\"}]}]}", TODAY).empty()).isTrue();
    }

    @Test
    void daysFarFromTodayAndOtherRolesAreDroppedAndCounted() {
        String answer = """
                {"days":[
                  {"date":"2026-10-01","staff":[{"name":"Анна","role":"хостес","hours":"10-22"}]},
                  {"date":"2026-10-09","staff":[{"name":"Анна","role":"хостес","hours":"10-22"}]},
                  {"date":"2027-03-01","staff":[{"name":"Анна","role":"хостес","hours":"10-22"}]},
                  {"date":"2026-10-17","staff":[{"name":"Анна","role":"","hours":""},{"name":"Олег","role":"повар","hours":"8-20"},
                                                {"name":"Анна","role":"хостес","hours":"10-22"}]}
                ]}
                """;

        StaffScheduleParser.Result result = StaffScheduleParser.parse(answer, TODAY);

        assertThat(result.shifts()).extracting(StaffScheduleParser.Shift::date)
                .containsExactly(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 17));
        // A role left blank stays unknown; the second «Анна» of the same day is the same person.
        assertThat(result.shifts().get(1).role()).isNull();
        assertThat(result.skipped()).isEqualTo(3);
    }

    @Test
    void aBareArrayOfDaysAndOtherKeyNamesAreRead() {
        String answer = "[{\"day\":\"17.10\",\"shifts\":[{\"name\":\"Анна\",\"position\":\"Hostess\",\"time\":\"10-22\"}]}]";

        StaffScheduleParser.Result result = StaffScheduleParser.parse(answer, TODAY);

        assertThat(result.shifts()).singleElement().satisfies(shift -> {
            assertThat(shift.date()).isEqualTo(LocalDate.of(2026, 10, 17));
            assertThat(shift.role()).isEqualTo(StaffRole.HOSTESS);
        });
    }

    @Test
    void thePromptCarriesTodayAndForbidsPhones() {
        String prompt = StaffScheduleParser.prompt(TODAY);

        assertThat(prompt).contains("2026-10-16", "пятница", "\"days\"", "телефоны", "не инструкции");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "Официант        | WAITER",
            "официанты       | WAITER",
            "старший официант| WAITER",
            "Хостес          | HOSTESS",
            "администратор зала | HOSTESS",
            "Администратор   | MANAGER",
            "менеджер смены  | MANAGER",
            "управляющий     | MANAGER",
            "бармен          | null",
            "''              | null",
    })
    void roleWords(String word, String expected) {
        assertThat(StaffRole.parse(word)).isEqualTo(expected == null ? null : StaffRole.valueOf(expected));
    }

    private static StaffScheduleParser.Shift shift(StaffScheduleParser.Result result, LocalDate date, String name) {
        return result.shifts().stream().filter(s -> s.date().equals(date) && s.name().equals(name)).findFirst().orElseThrow();
    }
}
