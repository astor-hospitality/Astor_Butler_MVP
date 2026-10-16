package museon_online.astor_butler.domain.roster;

import museon_online.astor_butler.domain.tips.TipsPersonalProperties;
import museon_online.astor_butler.domain.tips.TipsPersonalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A table nobody was given for the day goes to the waiter on shift of its zone, picked once and kept; /официант
 * overrides; nobody fits — the team. On the SQL production runs (staff portal, tips, waiter zones migrations).
 */
class TableWaitersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    private RosterTestDatabase database;
    private JdbcTemplate jdbc;
    private TipsPersonalRepository directory;
    private WaiterZoneRepository zones;
    private final FloorZones floor = mock(FloorZones.class);
    private final StaffRoster roster = mock(StaffRoster.class);
    private TableWaiters waiters;

    @BeforeEach
    void floorAndDirectory() {
        database = RosterTestDatabase.create();
        database.directory();
        jdbc = database.jdbc();
        directory = new TipsPersonalRepository(jdbc);
        zones = new WaiterZoneRepository(jdbc);
        waiters = new TableWaiters(directory, new TipsPersonalProperties(), zones, floor, roster);
        when(floor.zoneOfTable(anyString())).thenReturn(Optional.empty());
        for (String table : List.of("1", "2", "3", "4", "5", "6")) {
            when(floor.zoneOfTable(table)).thenReturn(Optional.of("WINDOW"));
        }
        when(floor.zoneOfTable("9")).thenReturn(Optional.of("BAR"));
        when(floor.zoneOfTable("19")).thenReturn(Optional.of("CORNER"));
    }

    @AfterEach
    void drop() throws Exception {
        database.close();
    }

    @Test
    void theWaiterOnShiftWhoseZoneHoldsTheTableIsPickedAndKeptAsAuto() {
        zones.add("AERIS", "vera", "WINDOW", "Борис");
        zones.add("AERIS", "gleb", "BAR", "Борис");
        onShift("vera", "gleb");

        TableWaiters.TableWaiter waiter = waiters.forRequest("5", DAY);

        assertThat(waiter).isEqualTo(new TableWaiters.TableWaiter("vera", "Вера Образцова", "WINDOW", true));
        assertThat(jdbc.queryForObject("SELECT assigned_by FROM table_waiter_assignments WHERE table_code = '5' AND shift_date = ?",
                String.class, java.sql.Date.valueOf(DAY))).isEqualTo("auto");
        assertThat(directory.waiterOfTable("AERIS", "5", DAY)).get().extracting(m -> m.staffId()).isEqualTo("vera");
        assertThat(waiters.forRequest("9", DAY).name()).isEqualTo("Глеб Макетов");
    }

    @Test
    void ofSeveralInTheZoneTheOneWithFewerTablesToday() {
        zones.add("AERIS", "vera", "WINDOW", "Борис");
        zones.add("AERIS", "egor", "WINDOW", "Борис");
        onShift("vera", "egor");
        directory.assign("AERIS", "1", DAY, "vera", "Борис");
        directory.assign("AERIS", "2", DAY, "vera", "Борис");
        directory.assign("AERIS", "3", DAY, "egor", "Борис");

        assertThat(waiters.forRequest("5", DAY).staffId()).isEqualTo("egor");
        // 2 and 2 now: the tie goes by name, «Вера» before «Егор»
        assertThat(waiters.forRequest("6", DAY).staffId()).isEqualTo("vera");
        // Вера 3, Егор 2 today; Егор's five tables of yesterday do not count
        for (String table : List.of("10", "11", "12", "13", "14")) {
            directory.assign("AERIS", table, DAY.minusDays(1), "egor", "Борис");
        }
        assertThat(waiters.forRequest("4", DAY).staffId()).isEqualTo("egor");
    }

    @Test
    void thePickIsStableForTheDayEvenWhenTheRosterChanges() {
        zones.add("AERIS", "vera", "WINDOW", "Борис");
        zones.add("AERIS", "egor", "WINDOW", "Борис");
        onShift("vera");
        assertThat(waiters.forRequest("5", DAY).staffId()).isEqualTo("vera");

        // Вера's hours ended, Егор is on now and has fewer tables: table 5 still is Вера's today.
        onShift("egor");
        TableWaiters.TableWaiter again = waiters.forRequest("5", DAY);

        assertThat(again).isEqualTo(new TableWaiters.TableWaiter("vera", "Вера Образцова", "WINDOW", false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM table_waiter_assignments", Integer.class)).isEqualTo(1);
        // a new day is a new pick
        assertThat(waiters.forRequest("5", DAY.plusDays(1)).staffId()).isEqualTo("egor");
    }

    @Test
    void theManagersAssignmentWinsBeforeAndAfterThePick() {
        zones.add("AERIS", "vera", "WINDOW", "Борис");
        onShift("vera", "egor");

        directory.assign("AERIS", "4", DAY, "egor", "Борис");
        assertThat(waiters.forRequest("4", DAY)).isEqualTo(new TableWaiters.TableWaiter("egor", "Егор", "WINDOW", false));
        assertThat(jdbc.queryForObject("SELECT assigned_by FROM table_waiter_assignments WHERE table_code = '4'", String.class))
                .isEqualTo("Борис");

        assertThat(waiters.forRequest("5", DAY).staffId()).isEqualTo("vera");
        directory.assign("AERIS", "5", DAY, "egor", "Борис"); // what /официант 5 Егор does
        assertThat(waiters.forRequest("5", DAY).staffId()).isEqualTo("egor");
        assertThat(waiters.assigned("5", DAY)).get().extracting(m -> m.displayName()).isEqualTo("Егор");
    }

    @Test
    void nobodyOnShiftInTheZoneIsTheTeamWithTheZone() {
        zones.add("AERIS", "vera", "WINDOW", "Борис");
        zones.add("AERIS", "petr", "CORNER", "Борис"); // left the directory
        onShift("gleb");

        assertThat(waiters.forRequest("5", DAY)).isEqualTo(TableWaiters.TableWaiter.team("WINDOW"));
        assertThat(waiters.forRequest("19", DAY)).isEqualTo(TableWaiters.TableWaiter.team("CORNER"));
        assertThat(waiters.forRequest("77", DAY)).isEqualTo(TableWaiters.TableWaiter.team(null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM table_waiter_assignments", Integer.class)).isZero();
    }

    @Test
    void withoutAScheduleNobodyIsPicked() {
        zones.add("AERIS", "vera", "WINDOW", "Борис");
        when(roster.onShiftNow(StaffRole.WAITER)).thenReturn(List.of());

        assertThat(waiters.forRequest("5", DAY).known()).isFalse();
    }

    @Test
    void aFailingLookupNeverStopsTheRequest() {
        TipsPersonalRepository broken = mock(TipsPersonalRepository.class);
        when(broken.waiterOfTable(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        TableWaiters failing = new TableWaiters(broken, new TipsPersonalProperties(), zones, floor, roster);

        assertThat(failing.forRequest("5", DAY)).isEqualTo(TableWaiters.TableWaiter.team(null));
        assertThat(failing.forRequest(null, DAY)).isEqualTo(TableWaiters.TableWaiter.team(null));
    }

    @Test
    void theStandingZonesAreKeptPerWaiterAndZone() {
        assertThat(zones.add("AERIS", "vera", "WINDOW", "Борис")).isTrue();
        assertThat(zones.add("AERIS", "vera", "WINDOW", "Борис")).isFalse();
        zones.add("AERIS", "vera", "BAR", "Борис");
        zones.add("AERIS", "egor", "WINDOW", "Борис");
        zones.add("AERIS", "petr", "WINDOW", "Борис");

        assertThat(zones.staffIn("AERIS", "WINDOW")).containsExactly("egor", "vera");
        assertThat(zones.all("AERIS")).extracting(WaiterZoneRepository.WaiterZone::zone, WaiterZoneRepository.WaiterZone::staffId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("BAR", "vera"),
                        org.assertj.core.groups.Tuple.tuple("WINDOW", "vera"),
                        org.assertj.core.groups.Tuple.tuple("WINDOW", "egor"));
        assertThat(zones.remove("AERIS", "vera", "WINDOW")).isTrue();
        assertThat(zones.remove("AERIS", "vera", "WINDOW")).isFalse();
        assertThat(zones.clearZone("AERIS", "WINDOW")).isEqualTo(2);
        assertThat(zones.staffIn("AERIS", "WINDOW")).isEmpty();
        assertThat(zones.staffIn("AERIS", "BAR")).containsExactly("vera");
    }

    private void onShift(String... staffIds) {
        List<StaffRoster.OnShift> shifts = java.util.Arrays.stream(staffIds)
                .map(id -> new StaffRoster.OnShift(id, name(id), StaffRole.WAITER, DAY, LocalTime.of(10, 0), LocalTime.of(22, 0)))
                .toList();
        when(roster.onShiftNow(StaffRole.WAITER)).thenReturn(shifts);
    }

    private String name(String staffId) {
        return jdbc.queryForObject("SELECT display_name FROM astor_staff_members WHERE tenant = 'AERIS' AND staff_id = ?",
                String.class, staffId);
    }
}
