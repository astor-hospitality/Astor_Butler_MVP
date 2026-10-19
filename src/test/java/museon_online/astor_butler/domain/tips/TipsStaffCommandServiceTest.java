package museon_online.astor_butler.domain.tips;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.roster.FloorZones;
import museon_online.astor_butler.domain.roster.WaiterZoneRepository;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The manager's /официант and /чаевые: who may, what is parsed, what is written, what is never printed. */
class TipsStaffCommandServiceTest {

    /** 2026-10-14 13:00 in Yekaterinburg: the shift day is the 14th. */
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final String LINK = "https://example.test/tip/daniil";

    private final TipsPersonalProperties properties = new TipsPersonalProperties();
    private final TipsPersonalRepository directory = mock(TipsPersonalRepository.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final TelegramSystemNotifier system = mock(TelegramSystemNotifier.class);
    private final TipsStaffCommandService service = new TipsStaffCommandService(
            properties, directory, notifications, system, "500", "600", "700", Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void featureOnWithTwoWaiters() {
        properties.setEnabled(true);
        when(directory.activeMembers("AERIS")).thenReturn(List.of(
                new TipStaffMember("anna", "Анна", "WAITER", true, true, null, null),
                new TipStaffMember("daniil", "Даниил", "WAITER", true, true, LINK, "СБП, Сбер"),
                new TipStaffMember("dasha", "Даша", "MANAGER", true, true, null, null)));
    }

    @Test
    void assignsTheWaiterToTheTableForTodayFromTheStaffChat() {
        assertThat(service.handle(staff(500L, "/официант 5 Даниилу"))).isTrue();

        verify(directory).assign("AERIS", "5", DAY, "daniil", "Даша (@dasha)");
        assertThat(reply("500")).isEqualTo("Стол 5 на 14.10 — Даниил (daniil).");
    }

    @Test
    void aRangeOfNumericTablesAndTheLatinAliasWork() {
        assertThat(service.handle(staff(600L, "/waiter@astor_butler_bot 1-3 anna"))).isTrue();

        verify(directory).assign("AERIS", "1", DAY, "anna", "Даша (@dasha)");
        verify(directory).assign("AERIS", "2", DAY, "anna", "Даша (@dasha)");
        verify(directory).assign("AERIS", "3", DAY, "anna", "Даша (@dasha)");
        assertThat(reply("600"))
                .startsWith("Столы 1, 2, 3 на 14.10 — Анна (anna).")
                .contains("Ссылки для чаевых у Анна нет")
                .contains("/чаевые anna");
    }

    @Test
    void anUnknownOrAmbiguousNameAssignsNobody() {
        when(directory.activeMembers("AERIS")).thenReturn(List.of(
                new TipStaffMember("anna", "Анна", "WAITER", true, true, null, null),
                new TipStaffMember("anna2", "Анна Петрова", "WAITER", true, true, null, null)));

        assertThat(service.handle(staff(500L, "/официант 5 Кирилл"))).isTrue();
        assertThat(reply("500")).contains("Не нашёл сотрудника «Кирилл»");

        assertThat(service.handle(staff(500L, "/официант 5 Анне"))).isTrue();
        assertThat(replies("500").get(1)).contains("Нашёл несколько").contains("Анна (anna)").contains("Анна Петрова (anna2)");

        assertThat(service.handle(staff(500L, "/официант 5 anna2"))).isTrue();
        verify(directory).assign("AERIS", "5", DAY, "anna2", "Даша (@dasha)");
        verify(directory, never()).assign(anyString(), anyString(), any(), eq("anna"), anyString());
    }

    @Test
    void aBadTableOrAMissingNameIsExplainedAndNothingIsWritten() {
        assertThat(service.handle(staff(500L, "/официант 5"))).isTrue();
        assertThat(service.handle(staff(500L, "/официант 9-1 Даниил"))).isTrue();
        assertThat(service.handle(staff(500L, "/официант стол?? Даниил"))).isTrue();

        verify(directory, never()).assign(anyString(), anyString(), any(), anyString(), any());
        assertThat(replies("500")).allSatisfy(text -> assertThat(text).contains("/официант"));
    }

    @Test
    void theDaysAssignmentsAreListedAndCanBeCleared() {
        when(directory.assignments("AERIS", DAY)).thenReturn(List.of(
                new TableWaiterAssignment("AERIS", "1", DAY, "daniil", "Даниил", "Даша", NOW),
                new TableWaiterAssignment("AERIS", "2", DAY, "anna", "Анна", "Даша", NOW)));
        when(directory.unassign("AERIS", "2", DAY)).thenReturn(true);

        assertThat(service.handle(staff(700L, "/официант"))).isTrue();
        assertThat(reply("700")).contains("на 14.10").contains("стол 1 — Даниил (daniil)").contains("стол 2 — Анна (anna)");

        assertThat(service.handle(staff(700L, "/официант 2 -"))).isTrue();
        verify(directory).unassign("AERIS", "2", DAY);
        assertThat(replies("700").get(1)).isEqualTo("Снял назначение: стол 2 на 14.10.");

        when(directory.assignments("AERIS", DAY)).thenReturn(List.of());
        assertThat(service.handle(staff(700L, "/официант"))).isTrue();
        assertThat(replies("700").get(2)).contains("официанты столам не назначены");
    }

    @Test
    void listsTheWaitersWithWhetherTheyHaveALinkAndNeverTheLinkItself() {
        assertThat(service.handle(staff(500L, "/чаевые"))).isTrue();

        String text = reply("500");
        assertThat(text).contains("Анна (anna) — ссылки нет").contains("Даниил (daniil) — ссылка есть · СБП, Сбер");
        assertThat(text).doesNotContain("Даша (dasha)").doesNotContain("https://").doesNotContain("example.test");
        verifyNoInteractions(system);
    }

    @Test
    void setsTheWaitersLinkWithANoteAndTellsTheSystemChatWithoutTheLink() {
        assertThat(service.handle(staff(600L, "/tips daniil https://example.test/tip/new Тинькофф без комиссии"))).isTrue();

        verify(directory).setTipLink("AERIS", "daniil", "https://example.test/tip/new", "Тинькофф без комиссии");
        assertThat(reply("600")).contains("Даниил (daniil) — сохранена").contains("Заметка: Тинькофф без комиссии").doesNotContain("https://");
        ArgumentCaptor<String> audit = ArgumentCaptor.forClass(String.class);
        verify(system).sendSystemNotice(audit.capture());
        assertThat(audit.getValue()).contains("Даниил (daniil)").contains("Даша (@dasha)").doesNotContain("https://");
    }

    @Test
    void aLinkThatIsNotHttpsIsRefusedAndNothingIsWritten() {
        assertThat(service.handle(staff(500L, "/чаевые daniil http://example.test/tip"))).isTrue();
        assertThat(service.handle(staff(500L, "/чаевые daniil https://"))).isTrue();

        verify(directory, never()).setTipLink(anyString(), anyString(), anyString(), any());
        assertThat(replies("500")).allSatisfy(text -> assertThat(text).contains("https://…"));
        verifyNoInteractions(system);
    }

    @Test
    void theLinkIsClearedWithADashAndOneWaitersStatusIsShownWithoutIt() {
        assertThat(service.handle(staff(500L, "/чаевые Даниил -"))).isTrue();
        verify(directory).setTipLink("AERIS", "daniil", null, null);
        assertThat(reply("500")).isEqualTo("Ссылка для чаевых: Даниил (daniil) — удалена.");

        assertThat(service.handle(staff(500L, "/чаевые daniil"))).isTrue();
        assertThat(replies("500").get(1)).isEqualTo("Даниил (daniil) — ссылка есть · СБП, Сбер");
    }

    @Test
    void aGuestOrAnUnknownGroupTypingTheCommandsIsNotTheManager() {
        assertThat(service.handle(staff(123L, "/официант 5 Даниил"))).isFalse();
        assertThat(service.handle(staff(-100999L, "/чаевые daniil " + LINK))).isFalse();

        verifyNoInteractions(directory, notifications, system);
    }

    @Test
    void withTheFeatureOffTheCommandsAreUnknown() {
        properties.setEnabled(false);

        assertThat(service.handle(staff(500L, "/официант 5 Даниил"))).isFalse();
        assertThat(service.handle(staff(500L, "/чаевые"))).isFalse();

        verifyNoInteractions(directory, notifications, system);
    }

    @Test
    void otherTextsAndCommandsStayWithTheirOwners() {
        assertThat(service.handle(staff(500L, "/stop"))).isFalse();
        assertThat(service.handle(staff(500L, "/manual on"))).isFalse();
        assertThat(service.handle(staff(500L, "официант 5 Даниил"))).isFalse();
        assertThat(service.handle(null)).isFalse();

        verifyNoInteractions(directory, notifications, system);
    }

    @Test
    void aDirectoryFailureIsAnsweredNotThrown() {
        when(directory.activeMembers("AERIS")).thenThrow(new IllegalStateException("db down"));

        assertThat(service.handle(staff(500L, "/официант 5 Даниил"))).isTrue();

        assertThat(reply("500")).contains("Не получилось выполнить команду");
    }

    @Test
    void tablesAreParsedAsSinglesRangesAndLists() {
        assertThat(TipsStaffCommandService.tables("5")).containsExactly("5");
        assertThat(TipsStaffCommandService.tables("05")).containsExactly("5");
        assertThat(TipsStaffCommandService.tables("1-3")).containsExactly("1", "2", "3");
        assertThat(TipsStaffCommandService.tables("1–3")).containsExactly("1", "2", "3");
        assertThat(TipsStaffCommandService.tables("1,4,2-3")).containsExactly("1", "4", "2", "3");
        assertThat(TipsStaffCommandService.tables("VIP-13")).containsExactly("VIP-13");
        assertThat(TipsStaffCommandService.tables("3-1")).isNull();
        assertThat(TipsStaffCommandService.tables("1-99")).isNull();
        assertThat(TipsStaffCommandService.tables("")).isNull();
        assertThat(TipsStaffCommandService.tables("5?")).isNull();
    }

    // ---- /зона ----------------------------------------------------------------------------------------------------

    private WaiterZoneRepository zonesWithFloor() {
        WaiterZoneRepository zones = mock(WaiterZoneRepository.class);
        FloorZones floor = mock(FloorZones.class);
        when(floor.zone(anyString())).thenReturn(null);
        when(floor.zone("WINDOW")).thenReturn("WINDOW");
        when(floor.zone("окно")).thenReturn("WINDOW");
        when(floor.zone("BAR")).thenReturn("BAR");
        when(floor.zoneOfTable(anyString())).thenReturn(java.util.Optional.empty());
        for (String table : List.of("1", "2", "3", "4", "5", "6")) {
            when(floor.zoneOfTable(table)).thenReturn(java.util.Optional.of("WINDOW"));
        }
        when(floor.zoneOfTable("9")).thenReturn(java.util.Optional.of("BAR"));
        when(floor.tablesOf("WINDOW")).thenReturn(List.of("1", "2", "3", "4", "5", "6"));
        when(floor.tablesOf("BAR")).thenReturn(List.of("9", "10", "BAR"));
        when(floor.zones()).thenReturn(List.of("WINDOW", "BAR"));
        service.setZones(zones, floor);
        return zones;
    }

    @Test
    void aWaiterIsGivenAStandingZoneByItsNameOrByItsTables() {
        WaiterZoneRepository zones = zonesWithFloor();

        assertThat(service.handle(staff(500L, "/зона WINDOW Даниил"))).isTrue();
        verify(zones).add("AERIS", "daniil", "WINDOW", "Даша (@dasha)");
        assertThat(reply("500")).startsWith("Даниил (daniil) — зона WINDOW (у окна): столы 1, 2, 3, 4, 5, 6.");

        assertThat(service.handle(staff(500L, "/зона 9 Анну"))).isTrue();
        verify(zones).add("AERIS", "anna", "BAR", "Даша (@dasha)");

        assertThat(service.handle(staff(500L, "/зона окно Даша"))).isTrue();
        assertThat(replies("500").get(2)).contains("в справочнике не официант");
    }

    @Test
    void aZoneIsTakenFromOneWaiterOrFromEveryone() {
        WaiterZoneRepository zones = zonesWithFloor();
        when(zones.remove("AERIS", "daniil", "WINDOW")).thenReturn(true);
        when(zones.clearZone("AERIS", "BAR")).thenReturn(2);

        assertThat(service.handle(staff(600L, "/зона WINDOW Даниил -"))).isTrue();
        verify(zones).remove("AERIS", "daniil", "WINDOW");
        assertThat(reply("600")).isEqualTo("Даниил (daniil) больше не в зоне WINDOW (у окна).");

        assertThat(service.handle(staff(600L, "/зона BAR -"))).isTrue();
        verify(zones).clearZone("AERIS", "BAR");
        assertThat(replies("600").get(1)).isEqualTo("Зона BAR (бар): убрал всех (2).");
        verify(zones, never()).add(any(), any(), any(), any());
    }

    @Test
    void theZonesAreListedWithTheirTablesAndWaiters() {
        WaiterZoneRepository zones = zonesWithFloor();
        when(zones.all("AERIS")).thenReturn(List.of(
                new WaiterZoneRepository.WaiterZone("anna", "Анна", "WINDOW"),
                new WaiterZoneRepository.WaiterZone("daniil", "Даниил", "WINDOW")));

        assertThat(service.handle(staff(500L, "/зона"))).isTrue();

        assertThat(reply("500"))
                .contains("• WINDOW (у окна) · столы 1, 2, 3, 4, 5, 6 — Анна, Даниил")
                .contains("• BAR (бар) · столы 9, 10, BAR — никого");
    }

    @Test
    void anUnknownZoneOrTableIsSaidAndNothingIsWritten() {
        WaiterZoneRepository zones = zonesWithFloor();

        service.handle(staff(500L, "/зона террасa Даниил"));
        service.handle(staff(500L, "/зона 77 Даниил"));
        service.handle(staff(500L, "/зона WINDOW"));

        List<String> replies = replies("500");
        assertThat(replies.get(0)).startsWith("Не понял зону «террасa». Зоны: WINDOW, BAR.");
        assertThat(replies.get(1)).startsWith("На плане зала нет: стол 77.");
        assertThat(replies.get(2)).startsWith("Кого поставить в зону WINDOW (у окна)?");
        verify(zones, never()).add(any(), any(), any(), any());
    }

    @Test
    void aGuestTypingTheZoneCommandIsNotHeard() {
        WaiterZoneRepository zones = zonesWithFloor();

        assertThat(service.handle(staff(421L, "/зона WINDOW Даниил"))).isFalse();
        verifyNoInteractions(zones);
    }

    @Test
    void anAutomaticPickIsMarkedInTheDaysList() {
        when(directory.assignments("AERIS", DAY)).thenReturn(List.of(
                new TableWaiterAssignment("AERIS", "5", DAY, "daniil", "Даниил", "auto", NOW)));

        service.handle(staff(500L, "/официант"));

        assertThat(reply("500")).contains("стол 5 — Даниил (daniil) · авто, по зоне");
    }

    private String reply(String chatId) {
        return replies(chatId).get(0);
    }

    private List<String> replies(String chatId) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).sendStaffNotice(eq(chatId), text.capture());
        return text.getAllValues();
    }

    private static IncomingMessage staff(Long chatId, String text) {
        return IncomingMessage.telegram(chatId, 42L, 1, 1, text, null, "Даша", null, "dasha", "ru", false, "corr");
    }
}
