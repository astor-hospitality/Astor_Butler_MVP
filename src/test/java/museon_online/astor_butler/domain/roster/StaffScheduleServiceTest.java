package museon_online.astor_butler.domain.roster;

import museon_online.astor_butler.domain.roster.StaffScheduleRepository.EntryStatus;
import museon_online.astor_butler.domain.roster.StaffScheduleRepository.Status;
import museon_online.astor_butler.domain.roster.StaffScheduleService.Button;
import museon_online.astor_butler.domain.roster.StaffScheduleService.CallbackResult;
import museon_online.astor_butler.domain.roster.StaffScheduleService.Recognition;
import museon_online.astor_butler.model.ModelEmbeddingRequest;
import museon_online.astor_butler.model.ModelEmbeddingResponse;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.model.ModelVisionRequest;
import museon_online.astor_butler.model.ModelVisionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixture picture through a stand-in vision model to a confirmed schedule, on the real migrations: the question
 * about unsure names, the buttons, «Верно / Исправить», who is on shift, /смена. Everyone is made up.
 */
class StaffScheduleServiceTest {

    private static final long CHAT = -1002768239841L;
    private static final ZoneId EKB = ZoneId.of("Asia/Yekaterinburg");
    /** Friday 16.10.2026, 14:00 in Yekaterinburg. */
    private static final Instant FRIDAY_2PM = ZonedDateTime.of(2026, 10, 16, 14, 0, 0, 0, EKB).toInstant();
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);

    private RosterTestDatabase database;
    private JdbcTemplate jdbc;
    private StaffScheduleProperties properties;
    private StaffScheduleRepository repository;
    private StaffRoster roster;
    private FakeVision vision;
    private StaffScheduleService service;
    private byte[] picture;

    @BeforeEach
    void setUp() throws IOException {
        database = RosterTestDatabase.create();
        database.directory();
        jdbc = database.jdbc();
        properties = new StaffScheduleProperties();
        properties.setEnabled(true);
        properties.setChatId(CHAT);
        properties.setThreadId(4);
        repository = new StaffScheduleRepository(jdbc);
        roster = new StaffRoster(properties, repository, Clock.fixed(FRIDAY_2PM, ZoneId.of("UTC")));
        vision = new FakeVision(StaffScheduleParserTest::fixtureAnswer);
        service = new StaffScheduleService(properties, repository, roster, vision);
        try (InputStream input = getClass().getResourceAsStream("/roster/schedule-week.png")) {
            picture = input.readAllBytes();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void thePictureGoesToTheVisionModelWithTodayAndRoomForAWholeWeek() {
        service.recognize(picture, "image/png", CHAT, 100, "Даша (@dasha)");

        ModelVisionRequest request = vision.requests.get(0);
        assertThat(request.imageBase64()).isEqualTo(Base64.getEncoder().encodeToString(picture));
        assertThat(request.mimeType()).isEqualTo("image/png");
        assertThat(request.model()).isNull(); // the provider's own vision model
        assertThat(request.prompt()).contains("2026-10-16");
        assertThat(request.maxTokens()).isEqualTo(2048);
        assertThat(request.purpose()).isEqualTo("staff-schedule");
    }

    @Test
    void unsureNamesAreAskedInOneMessageWithButtons() {
        Recognition recognition = service.recognize(picture, "image/png", CHAT, 100, "Даша (@dasha)").orElseThrow();

        StaffScheduleRepository.Schedule schedule = repository.schedule(recognition.scheduleId()).orElseThrow();
        assertThat(schedule.status()).isEqualTo(Status.CLARIFYING);
        assertThat(schedule.firstDate()).isEqualTo(FRIDAY);
        assertThat(schedule.lastDate()).isEqualTo(FRIDAY.plusDays(2));
        assertThat(schedule.unreadable()).isEqualTo(1);

        String text = recognition.reply().text();
        assertThat(text).contains("уточните", "«Дина» (хостес), смен: 2 — Дина Пробная или Дина Шаблонова?",
                "«Глеп Макетов» (официант), смен: 2 — Глеб Макетов?", "«Егор Пробный» (официант), смен: 2 — Егор?");
        // Certain and unknown names are not asked about.
        assertThat(text).doesNotContain("Алиса", "Борис", "Пётр", "Олеся");
        List<List<Button>> buttons = recognition.reply().buttons();
        assertThat(buttons).hasSize(4);
        // In the order the names first appear: Friday's two, then Saturday's Дина.
        assertThat(buttons.get(0)).extracting(Button::label).containsExactly("1 · Глеб М.", "1 · никто");
        assertThat(buttons.get(1)).extracting(Button::label).containsExactly("2 · Егор", "2 · никто");
        assertThat(buttons.get(2)).extracting(Button::label).containsExactly("3 · Дина П.", "3 · Дина Ш.", "3 · никто");
        assertThat(buttons.get(3)).extracting(Button::label).containsExactly("✏️ Исправить");
        assertThat(buttons).flatExtracting(row -> row).allSatisfy(button -> assertThat(button.data().length()).isLessThanOrEqualTo(64));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_shift_entries", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForList("SELECT DISTINCT raw_name FROM staff_shift_entries WHERE match_status = 'UNKNOWN'", String.class))
                .containsExactlyInAnyOrder("Пётр Неизвестный", "Олеся");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_shift_entries WHERE raw_name LIKE '%7%' OR raw_name LIKE '%900%'", Integer.class))
                .isZero();
        // Nothing counts until the schedule is confirmed.
        assertThat(roster.onShiftNow()).isEmpty();
    }

    @Test
    void choosingEveryNameTurnsTheQuestionIntoTheSummaryAndVernoConfirmsIt() {
        Recognition recognition = service.recognize(picture, "image/png", CHAT, 100, "Даша (@dasha)").orElseThrow();
        long id = recognition.scheduleId();

        CallbackResult dina = press(row(recognition, "Дина").get(0));
        assertThat(dina.answerText()).isEqualTo("«Дина» — Дина Пробная.");
        assertThat(dina.edit().text()).doesNotContain("«Дина»").contains("«Глеп Макетов»", "«Егор Пробный»");
        assertThat(dina.edit().buttons()).hasSize(3);

        assertThat(press(dina.edit().buttons().get(0).get(0)).answerText()).isEqualTo("«Глеп Макетов» — Глеб Макетов.");
        CallbackResult last = press(firstButtonOf(id)); // Егор Пробный → Егор
        assertThat(last.answerText()).isEqualTo("«Егор Пробный» — Егор.");
        assertThat(repository.schedule(id).orElseThrow().status()).isEqualTo(Status.PROPOSED);
        assertThat(last.edit().text())
                .contains("<b>График 16.10–18.10</b>")
                .contains("На сегодня: хостес Алиса Тестова; менеджер Борис Примеров; официанты Вера Образцова, Глеб Макетов, Егор.")
                .contains("Нет в справочнике: Пётр Неизвестный (официант), Олеся (официант) — им поручения не уйдут.")
                .contains("Не разобрал строк: 1")
                .contains("Пока не нажали «Верно»");
        assertThat(last.edit().buttons()).singleElement().satisfies(row ->
                assertThat(row).extracting(Button::label).containsExactly("✅ Верно", "✏️ Исправить"));
        assertThat(roster.onShiftNow()).isEmpty();

        CallbackResult verno = press(last.edit().buttons().get(0).get(0));

        assertThat(verno.answerText()).isEqualTo("График принят.");
        assertThat(verno.edit().buttons()).isEmpty();
        assertThat(verno.edit().text()).contains("✅ Верно — Маша (@masha). График действует.");
        StaffScheduleRepository.Schedule confirmed = repository.schedule(id).orElseThrow();
        assertThat(confirmed.status()).isEqualTo(Status.CONFIRMED);
        assertThat(confirmed.decidedBy()).isEqualTo("Маша (@masha)");
        assertThat(press(new Button("", "roster:ok:" + id)).answerText()).isEqualTo("Уже подтверждено.");
    }

    @Test
    void whoIsOnShiftNowByRoleAfterVerno() {
        confirmFixture(100);

        assertThat(roster.onShiftNow(StaffRole.WAITER)).extracting(StaffRoster.OnShift::staffId)
                .containsExactlyInAnyOrder("vera", "gleb", "egor");
        assertThat(roster.onShiftNow(StaffRole.HOSTESS)).extracting(StaffRoster.OnShift::staffId).containsExactly("alisa");
        assertThat(roster.onShiftNow(StaffRole.MANAGER)).extracting(StaffRoster.OnShift::staffId).containsExactly("boris");
        // Saturday: Дина was settled as Дина Пробная for every day she appears on.
        assertThat(roster.onShift(FRIDAY.plusDays(1))).extracting(StaffRoster.OnShift::staffId)
                .containsExactlyInAnyOrder("alisa", "dina.pr", "boris", "gleb");
        // 01:00 on Sunday: only Борис, whose Saturday shift runs until 02:00.
        assertThat(roster.onShiftAt(ZonedDateTime.of(2026, 10, 18, 1, 0, 0, 0, EKB)))
                .extracting(StaffRoster.OnShift::staffId).containsExactly("boris");
        // 09:00 on Friday: nobody has started; Егор has no hours and counts for the whole day.
        assertThat(roster.onShiftAt(ZonedDateTime.of(2026, 10, 16, 9, 0, 0, 0, EKB)))
                .extracting(StaffRoster.OnShift::staffId).containsExactly("egor");
        // A day the schedule does not cover is nobody's.
        assertThat(roster.onShift(FRIDAY.plusDays(3))).isEmpty();
    }

    @Test
    void someoneWhoLeftTheDirectoryIsNoLongerOnShift() {
        confirmFixture(100);

        jdbc.update("UPDATE astor_staff_members SET active = FALSE WHERE tenant = 'AERIS' AND staff_id = 'vera'");

        assertThat(roster.onShiftNow(StaffRole.WAITER)).extracting(StaffRoster.OnShift::staffId)
                .containsExactlyInAnyOrder("gleb", "egor");
    }

    @Test
    void theChoiceIsRememberedSoTheNextScheduleAsksNothing() {
        confirmFixture(100);

        Recognition next = service.recognize(picture, "image/png", CHAT, 101, "Даша (@dasha)").orElseThrow();

        assertThat(repository.schedule(next.scheduleId()).orElseThrow().status()).isEqualTo(Status.PROPOSED);
        assertThat(next.reply().text()).startsWith("<b>График 16.10–18.10</b>");
        assertThat(repository.entries(next.scheduleId())).filteredOn(entry -> entry.status() == EntryStatus.AMBIGUOUS).isEmpty();
    }

    @Test
    void ispravitKeepsThePreviousConfirmedScheduleInForce() {
        confirmFixture(100);
        vision.answer = () -> """
                {"days":[{"date":"2026-10-16","staff":[{"name":"Вера Образцова","role":"хостес","hours":"10-22"}]}]}
                """;
        Recognition wrong = service.recognize(picture, "image/png", CHAT, 101, "Даша (@dasha)").orElseThrow();

        CallbackResult fix = press(wrong.reply().buttons().get(0).get(1));

        assertThat(fix.answerText()).isEqualTo("График не принят.");
        assertThat(fix.edit().text()).contains("✏️ Не принят — Маша (@masha)", "пока действует прежний подтверждённый график");
        assertThat(fix.edit().buttons()).isEmpty();
        assertThat(roster.onShiftNow(StaffRole.HOSTESS)).extracting(StaffRoster.OnShift::staffId).containsExactly("alisa");
        assertThat(press(new Button("", "roster:ok:" + wrong.scheduleId())).answerText()).isEqualTo("График не принят — пришлите новое фото.");
        assertThat(repository.schedule(wrong.scheduleId()).orElseThrow().status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void aLaterConfirmedScheduleReplacesTheEarlierOneForItsDays() {
        confirmFixture(100);
        vision.answer = () -> """
                {"days":[{"date":"2026-10-16","staff":[{"name":"Вера Образцова","role":"хостес","hours":"10-22"}]}]}
                """;
        Recognition fresh = service.recognize(picture, "image/png", CHAT, 101, "Даша (@dasha)").orElseThrow();
        press(fresh.reply().buttons().get(0).get(0));

        assertThat(roster.onShiftNow()).extracting(StaffRoster.OnShift::staffId).containsExactly("vera");
        assertThat(roster.onShiftNow(StaffRole.HOSTESS)).extracting(StaffRoster.OnShift::staffId).containsExactly("vera");
        // Saturday is not in the new picture: the earlier schedule still answers for it.
        assertThat(roster.onShift(FRIDAY.plusDays(1))).isNotEmpty();
    }

    @Test
    void theSamePictureTwiceIsRecognisedOnce() {
        assertThat(service.recognize(picture, "image/png", CHAT, 100, null)).isPresent();
        assertThat(service.recognize(picture, "image/png", CHAT, 100, null)).isEmpty();
        assertThat(vision.requests).hasSize(1);
    }

    @Test
    void anUnavailableModelOrAnUnreadableAnswerStoresNothing() {
        vision.answer = () -> {
            throw new IllegalStateException("cloud.ru API key is not configured");
        };
        Recognition failed = service.recognize(picture, "image/png", CHAT, 100, null).orElseThrow();
        assertThat(failed.scheduleId()).isNull();
        assertThat(failed.reply().text()).isEqualTo(StaffScheduleTexts.MODEL_UNAVAILABLE);
        assertThat(failed.reply().buttons()).isEmpty();

        vision.answer = () -> null;
        vision.fallback = true;
        assertThat(service.recognize(picture, "image/png", CHAT, 101, null).orElseThrow().reply().text())
                .isEqualTo(StaffScheduleTexts.MODEL_UNAVAILABLE);

        vision.fallback = false;
        vision.answer = () -> "На изображении таблица, но текст размыт.";
        assertThat(service.recognize(picture, "image/png", CHAT, 102, null).orElseThrow().reply().text())
                .isEqualTo(StaffScheduleTexts.UNREADABLE);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_shift_schedules", Integer.class)).isZero();
    }

    @Test
    void aTooLargePictureIsNotSentToTheModel() {
        properties.setMaxImageBytes(1000);

        assertThat(service.recognize(picture, "image/png", CHAT, 100, null).orElseThrow().reply().text())
                .isEqualTo(StaffScheduleTexts.TOO_LARGE);
        assertThat(vision.requests).isEmpty();
    }

    @Test
    void buttonsWorkOnlyInTheStaffChatAndOnlyOnTheirOwnSchedule() {
        Recognition recognition = service.recognize(picture, "image/png", CHAT, 100, null).orElseThrow();
        Button dina = row(recognition, "Дина").get(0);

        assertThat(service.callback(dina.data(), 12345L, "гость").answerText()).contains("только в чате персонала");
        assertThat(service.callback("roster:pick:999999:0", CHAT, "Маша").answerText()).isEqualTo("Кнопка устарела.");
        assertThat(service.callback("roster:drop:1", CHAT, "Маша").answerText()).isEqualTo("Кнопка устарела.");
        assertThat(service.callback("lunch:ok:1", CHAT, "Маша").handled()).isFalse();
        assertThat(repository.schedule(recognition.scheduleId()).orElseThrow().status()).isEqualTo(Status.CLARIFYING);
    }

    @Test
    void nikto_leavesTheNameWithoutAnAddresseeAndRemembersNothing() {
        Recognition recognition = service.recognize(picture, "image/png", CHAT, 100, null).orElseThrow();

        CallbackResult nobody = press(row(recognition, "Дина").get(2));

        assertThat(nobody.answerText()).contains("нет в справочнике");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_shift_entries WHERE raw_name = 'Дина' AND match_status = 'DISMISSED'",
                Integer.class)).isEqualTo(2);
        assertThat(repository.aliases("AERIS")).isEmpty();
    }

    @Test
    void smenaSaysWhoIsOnShiftNowAndForADay() {
        assertThat(service.isShiftCommand("/смена")).isTrue();
        assertThat(service.isShiftCommand("/shift@AerisButlerBot завтра")).isTrue();
        assertThat(service.isShiftCommand("/сменаX")).isFalse();
        assertThat(service.isShiftCommand("смена")).isFalse();

        assertThat(service.shiftCommand("/смена")).isEqualTo(StaffScheduleTexts.noSchedule(FRIDAY));

        Recognition pending = service.recognize(picture, "image/png", CHAT, 100, null).orElseThrow();
        assertThat(service.shiftCommand("/смена")).contains("Подтверждённого графика на 16.10 нет", "График 16.10–18.10 ждёт «Верно»");

        settleAndConfirm(pending);
        assertThat(service.shiftCommand("/смена")).isEqualTo("""
                <b>Сейчас на смене</b> (16.10, 14:00):
                хостес — Алиса Тестова (до 22:00)
                менеджер — Борис Примеров (до 23:00)
                официанты — Вера Образцова (до 22:00), Глеб Макетов (до 00:00), Егор""");
        assertThat(service.shiftCommand("/смена завтра")).isEqualTo("""
                <b>На смене 17.10</b>:
                хостес — Алиса Тестова 10:00–22:00, Дина Пробная 12:00–23:00
                менеджер — Борис Примеров 11:00–02:00
                официант — Глеб Макетов 12:00–00:00""");
        assertThat(service.shiftCommand("/смена 20.10")).isEqualTo(StaffScheduleTexts.noSchedule(LocalDate.of(2026, 10, 20)));
        assertThat(service.shiftCommand("/смена когда-нибудь")).startsWith("Не понял день");
    }

    @Test
    void offTheRosterKnowsNobody() {
        confirmFixture(100);

        properties.setEnabled(false);

        assertThat(roster.onShiftNow()).isEmpty();
        assertThat(roster.onShift(FRIDAY)).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void confirmFixture(int messageId) {
        settleAndConfirm(service.recognize(picture, "image/png", CHAT, messageId, "Даша (@dasha)").orElseThrow());
    }

    private void settleAndConfirm(Recognition recognition) {
        long id = recognition.scheduleId();
        while (repository.schedule(id).orElseThrow().status() == Status.CLARIFYING) {
            press(firstButtonOf(id));
        }
        press(new Button("", "roster:ok:" + id));
        assertThat(repository.schedule(id).orElseThrow().status()).isEqualTo(Status.CONFIRMED);
    }

    /** The row of buttons whose first candidate's label has that name in it. */
    private static List<Button> row(Recognition recognition, String name) {
        return recognition.reply().buttons().stream()
                .filter(row -> row.get(0).label().contains(name))
                .findFirst().orElseThrow();
    }

    private Button firstButtonOf(long scheduleId) {
        return service.render(scheduleId).buttons().get(0).get(0);
    }

    private CallbackResult press(Button button) {
        CallbackResult result = service.callback(button.data(), CHAT, "Маша (@masha)");
        assertThat(result.handled()).isTrue();
        return result;
    }

    /** A vision model that answers what it is told to and remembers what it was asked. */
    static final class FakeVision implements ModelGateway {

        final List<ModelVisionRequest> requests = new ArrayList<>();
        Supplier<String> answer;
        boolean fallback;

        FakeVision(Supplier<String> answer) {
            this.answer = answer;
        }

        @Override
        public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
            requests.add(request);
            String text = answer.get();
            return new ModelVisionResponse(text == null ? "" : text, "test", "test-vision", null,
                    Duration.ofMillis(5), fallback, Map.of());
        }

        @Override
        public ModelTextResponse generateText(ModelTextRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelEmbeddingResponse generateEmbedding(ModelEmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
