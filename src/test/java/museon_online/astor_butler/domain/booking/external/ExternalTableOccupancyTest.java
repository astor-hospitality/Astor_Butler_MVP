package museon_online.astor_butler.domain.booking.external;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalTableOccupancyTest {

    private static final List<String> BUTLER_TABLES = List.of("1", "2", "5", "13", "BAR");

    @Test
    void tableNamesAreComparedByTheirNumberOrLetters() {
        assertThat(ExternalTableCodes.key("5")).isEqualTo("5");
        assertThat(ExternalTableCodes.key(" Стол 5 ")).isEqualTo("5");
        assertThat(ExternalTableCodes.key("VIP 13")).isEqualTo("13");
        assertThat(ExternalTableCodes.key("05")).isEqualTo("5");
        assertThat(ExternalTableCodes.key("0")).isEqualTo("0");
        assertThat(ExternalTableCodes.key("bar")).isEqualTo("BAR");
        assertThat(ExternalTableCodes.key("Зал 2, стол 7")).isEqualTo("ЗАЛ2СТОЛ7");
        assertThat(ExternalTableCodes.key(" ")).isEqualTo("");
        assertThat(ExternalTableCodes.key(null)).isEqualTo("");
    }

    @Test
    void offeredTablesStayOpenAndEveryOtherTableIsBlocked() {
        ExternalTableOccupancy occupancy = ExternalTableOccupancy.from(
                answer("AVAILABLE", false, "Стол 1", "VIP 13"), () -> BUTLER_TABLES);

        assertThat(occupancy.authoritative()).isTrue();
        assertThat(occupancy.blocks("1")).isFalse();
        assertThat(occupancy.blocks("13")).isFalse();
        assertThat(occupancy.blocks("2")).isTrue();
        assertThat(occupancy.blocks("BAR")).isTrue();
    }

    @Test
    void aFullRestaurantBlocksEveryTable() {
        ExternalTableOccupancy occupancy = ExternalTableOccupancy.from(
                answer("NO_TABLES_AVAILABLE", false), () -> BUTLER_TABLES);

        assertThat(occupancy.authoritative()).isTrue();
        assertThat(occupancy.blocks("1")).isTrue();
        assertThat(occupancy.blocks("5")).isTrue();
    }

    @Test
    void aProviderThatIsOffOrFailedBlocksNothing() {
        ExternalTableOccupancy off = ExternalTableOccupancy.from(
                ExternalAvailabilityResult.unavailableBecauseUnconfigured("SABY", List.of("SABY_POINT_ID")),
                () -> BUTLER_TABLES);
        ExternalTableOccupancy timeout = ExternalTableOccupancy.from(
                new ExternalAvailabilityResult(false, true, "SABY", "PROVIDER_TIMEOUT", "", List.of(), Map.of()),
                () -> BUTLER_TABLES);

        assertThat(off.authoritative()).isFalse();
        assertThat(off.blocks("1")).isFalse();
        assertThat(timeout.authoritative()).isFalse();
        assertThat(timeout.reason()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(timeout.blocks("1")).isFalse();
    }

    @Test
    void anIncompleteTableListBlocksNothing() {
        ExternalTableOccupancy occupancy = ExternalTableOccupancy.from(
                answer("AVAILABLE", true, "1"), () -> BUTLER_TABLES);

        assertThat(occupancy.authoritative()).isFalse();
        assertThat(occupancy.reason()).isEqualTo(ExternalTableOccupancy.REASON_INCOMPLETE_TABLE_LIST);
        assertThat(occupancy.blocks("2")).isFalse();
    }

    @Test
    void tableNamesThatMeanNoButlerTableBlockNothing() {
        ExternalTableOccupancy occupancy = ExternalTableOccupancy.from(
                answer("AVAILABLE", false, "Камин", "Терраса"), () -> BUTLER_TABLES);

        assertThat(occupancy.authoritative()).isFalse();
        assertThat(occupancy.reason()).isEqualTo(ExternalTableOccupancy.REASON_TABLE_NAMES_DO_NOT_MATCH);
        assertThat(occupancy.blocks("1")).isFalse();
    }

    private ExternalAvailabilityResult answer(String status, boolean hasMore, String... freeTableNames) {
        List<Map<String, Object>> candidates = Arrays.stream(freeTableNames)
                .map(name -> Map.<String, Object>of("hallId", 271L, "tableId", 1L, "tableName", name, "capacity", 4))
                .toList();
        return new ExternalAvailabilityResult(
                !candidates.isEmpty(),
                true,
                "SABY",
                status,
                "",
                List.of(),
                Map.of("candidates", candidates, "hasMore", hasMore)
        );
    }
}
