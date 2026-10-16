package museon_online.astor_butler.domain.roster;

import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.VenueTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The floor's zones as the staff says them: the zone of a table and the tables of a zone come from {@code venue_tables}
 * (AERIS: WINDOW 1–6, WINE_ROOM 7–8, BAR 9, 10 and the counter, SOFT_LOUNGE 11–12, VIP_ZONE 13–15, CENTER_STAGE 16–18,
 * CORNER 19); the words — «окно», «бар», «вип», «винная» — are mapped here.
 */
@Component
public class FloorZones {

    /** The zone codes of the plan with the word the staff card shows. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    /** A word the manager may type for a zone (lower case, ё as е). */
    private static final Map<String, String> WORDS = new LinkedHashMap<>();

    static {
        LABELS.put("WINDOW", "у окна");
        LABELS.put("WINE_ROOM", "винная комната");
        LABELS.put("BAR", "бар");
        LABELS.put("SOFT_LOUNGE", "камерная гостиная");
        LABELS.put("VIP_ZONE", "VIP");
        LABELS.put("CENTER_STAGE", "центр зала");
        LABELS.put("CORNER", "угловой стол");
        for (String word : List.of("окно", "окна", "окон", "окну", "window")) {
            WORDS.put(word, "WINDOW");
        }
        for (String word : List.of("винная", "винной", "вино", "wine", "wine_room")) {
            WORDS.put(word, "WINE_ROOM");
        }
        for (String word : List.of("бар", "бара", "bar")) {
            WORDS.put(word, "BAR");
        }
        for (String word : List.of("гостиная", "гостиной", "лаунж", "soft", "lounge", "soft_lounge")) {
            WORDS.put(word, "SOFT_LOUNGE");
        }
        for (String word : List.of("вип", "vip", "vip_zone")) {
            WORDS.put(word, "VIP_ZONE");
        }
        for (String word : List.of("центр", "центра", "сцена", "сцены", "center", "center_stage")) {
            WORDS.put(word, "CENTER_STAGE");
        }
        for (String word : List.of("угол", "угловой", "угла", "corner")) {
            WORDS.put(word, "CORNER");
        }
    }

    private final TableReservationRepository tables;
    private final String venueCode;

    public FloorZones(TableReservationRepository tables, @Value("${astor.business-lunch.default-venue-code:AERIS}") String venueCode) {
        this.tables = tables;
        this.venueCode = venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.trim();
    }

    /** The zone a zone word names: a code of the plan or one of the staff's words; null for anything else. */
    public String zone(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        String said = word.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        String known = WORDS.get(said);
        if (known != null) {
            return known;
        }
        String code = said.toUpperCase(Locale.ROOT);
        return LABELS.containsKey(code) || zones().contains(code) ? code : null;
    }

    /** The zone of the table on the plan; empty for a table the plan does not have. */
    public Optional<String> zoneOfTable(String tableCode) {
        if (tableCode == null || tableCode.isBlank()) {
            return Optional.empty();
        }
        return tables.findTableByCode(venueCode, tableCode.trim())
                .map(VenueTable::zone)
                .filter(zone -> zone != null && !zone.isBlank());
    }

    /** The active tables of the zone, in their natural order (1, 2, …, 10). */
    public List<String> tablesOf(String zone) {
        return activeTables().stream()
                .filter(table -> zone.equals(table.zone()))
                .map(VenueTable::tableCode)
                .sorted(NATURAL)
                .toList();
    }

    /** The zones of the plan, in the order of {@link #LABELS} and then the others. */
    public List<String> zones() {
        Set<String> zones = new LinkedHashSet<>();
        List<String> onPlan = activeTables().stream().map(VenueTable::zone).filter(zone -> zone != null && !zone.isBlank()).toList();
        for (String code : LABELS.keySet()) {
            if (onPlan.contains(code)) {
                zones.add(code);
            }
        }
        zones.addAll(onPlan);
        return new ArrayList<>(zones);
    }

    /** «WINDOW (у окна)»; the code alone for a zone without a word. */
    public static String label(String zone) {
        String word = zone == null ? null : LABELS.get(zone);
        return word == null ? String.valueOf(zone) : zone + " (" + word + ")";
    }

    private List<VenueTable> activeTables() {
        return tables.findTables(venueCode).stream().filter(table -> !Boolean.FALSE.equals(table.active())).toList();
    }

    /** 1, 2, …, 10 before the letters (BAR). */
    static final Comparator<String> NATURAL = Comparator
            .comparing((String code) -> !code.matches("\\d+"))
            .thenComparing(code -> code.matches("\\d+") ? Integer.parseInt(code) : 0)
            .thenComparing(Comparator.naturalOrder());
}
