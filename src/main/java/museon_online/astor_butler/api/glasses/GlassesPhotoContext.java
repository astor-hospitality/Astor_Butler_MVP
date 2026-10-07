package museon_online.astor_butler.api.glasses;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client shift correlation only. Never a task, table, assignment or completion claim. */
public record GlassesPhotoContext(String sessionId, String scenarioCode, String stageCode, int revision) {
    private static final Map<String, String> LUNCH_STAGES = stages(
            "TABLE_PREPARE", "Проверь видимые чистоту стола, два места и проход.",
            "PLACE_SETTINGS", "Проверь видимые два комплекта приборов, салфеток и бокалов.",
            "WATER_MENU", "Проверь видимые воду и меню; не придумывай меню или пожелания гостей.",
            "FINAL_CHECK", "Посмотри на стол целиком: два места и аккуратность сервировки.");
    private static final Map<String, String> SHIFT_STAGES = stages(
            "SHIFT_ASSIST", "Сотрудник на смене спрашивает по работе; отвечай только по видимому и по справочным данным.");
    static final Map<String, Map<String, String>> SCENARIOS = Map.of(
            "BUSINESS_LUNCH_TWO", LUNCH_STAGES, "SHIFT", SHIFT_STAGES);

    public GlassesPhotoContext {
        try {
            Map<String, String> stages = scenarioCode == null ? null : SCENARIOS.get(scenarioCode);
            if (sessionId == null || !UUID.fromString(sessionId).toString().equalsIgnoreCase(sessionId)
                    || stages == null || !stages.containsKey(stageCode)
                    || revision < 1 || revision > 10000) throw new IllegalArgumentException();
            sessionId = UUID.fromString(sessionId).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid session context");
        }
    }

    static List<String> stageOrder(String scenarioCode) {
        Map<String, String> stages = SCENARIOS.get(scenarioCode);
        return stages == null ? List.of() : List.copyOf(stages.keySet());
    }

    boolean shift() { return "SHIFT".equals(scenarioCode); }

    String prompt() {
        return title() + SCENARIOS.get(scenarioCode).get(stageCode)
                + " Если деталь не видна, скажи об этом. Фото не подтверждает выполнение поручения.\n";
    }

    /** Stage hint for a spoken or typed question; the stage never changes what the assistant may claim. */
    String questionPrompt() {
        return title() + "Текущий шаг сотрудника: " + stageCode + ". " + SCENARIOS.get(scenarioCode).get(stageCode) + "\n";
    }

    private String title() {
        return shift() ? "Смена сотрудника ресторана. " : "Бизнес-ланч на двух гостей. ";
    }

    String signature() { return sessionId + ":" + scenarioCode + ":" + stageCode + ":" + revision; }

    private static Map<String, String> stages(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }
}
