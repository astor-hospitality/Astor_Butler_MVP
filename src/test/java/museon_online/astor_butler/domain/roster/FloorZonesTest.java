package museon_online.astor_butler.domain.roster;

import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.VenueTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The floor's zones from venue_tables and the words the staff types for them. */
class FloorZonesTest {

    private final TableReservationRepository tables = mock(TableReservationRepository.class);
    private final FloorZones floor = new FloorZones(tables, "AERIS");

    @Test
    void zoneWordsAndCodesNameTheZones() {
        when(tables.findTables("AERIS")).thenReturn(plan());

        assertThat(floor.zone("WINDOW")).isEqualTo("WINDOW");
        assertThat(floor.zone("window")).isEqualTo("WINDOW");
        assertThat(floor.zone("Окно")).isEqualTo("WINDOW");
        assertThat(floor.zone("вип")).isEqualTo("VIP_ZONE");
        assertThat(floor.zone("винная")).isEqualTo("WINE_ROOM");
        assertThat(floor.zone("бар")).isEqualTo("BAR");
        assertThat(floor.zone("TERRACE")).isNull();
        assertThat(floor.zone("5")).isNull();
        assertThat(floor.zone(" ")).isNull();
    }

    @Test
    void theTablesOfAZoneAreInTheirNaturalOrderAndOnlyActiveOnes() {
        when(tables.findTables("AERIS")).thenReturn(plan());

        assertThat(floor.tablesOf("BAR")).containsExactly("9", "10", "BAR");
        assertThat(floor.tablesOf("WINDOW")).containsExactly("1", "2");
        assertThat(floor.zones()).containsExactly("WINDOW", "BAR");
        assertThat(FloorZones.label("WINDOW")).isEqualTo("WINDOW (у окна)");
        assertThat(FloorZones.label("TERRACE")).isEqualTo("TERRACE");
    }

    @Test
    void theZoneOfATableIsThePlans() {
        when(tables.findTableByCode("AERIS", "9")).thenReturn(Optional.of(table("9", "BAR", true)));
        when(tables.findTableByCode("AERIS", "77")).thenReturn(Optional.empty());

        assertThat(floor.zoneOfTable("9")).contains("BAR");
        assertThat(floor.zoneOfTable("77")).isEmpty();
        assertThat(floor.zoneOfTable(null)).isEmpty();
    }

    private static List<VenueTable> plan() {
        return List.of(table("10", "BAR", true), table("2", "WINDOW", true), table("BAR", "BAR", true), table("1", "WINDOW", true),
                table("9", "BAR", true), table("3", "WINDOW", false));
    }

    private static VenueTable table(String code, String zone, boolean active) {
        return new VenueTable(1L, "AERIS", code, "Стол " + code, zone, 1, 4, null, true, active, 2, null, 1, null, null);
    }
}
