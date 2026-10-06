package museon_online.astor_butler.api.glasses;

import java.util.Map;
import java.util.UUID;

/** Client training correlation only. Never a task, table, assignment or completion claim. */
public record GlassesPhotoContext(String sessionId, String scenarioCode, String stageCode, int revision) {
    private static final Map<String, String> STAGES = Map.of(
            "TABLE_PREPARE", "Проверь видимые чистоту стола, два места и проход.",
            "PLACE_SETTINGS", "Проверь видимые два комплекта приборов, салфеток и бокалов.",
            "WATER_MENU", "Проверь видимые воду и меню; не придумывай меню или пожелания гостей.",
            "FINAL_CHECK", "Посмотри на стол целиком: два места и аккуратность сервировки.");

    public GlassesPhotoContext {
        try {
            if (sessionId == null || !UUID.fromString(sessionId).toString().equalsIgnoreCase(sessionId)
                    || !"BUSINESS_LUNCH_TWO".equals(scenarioCode) || !STAGES.containsKey(stageCode)
                    || revision < 1 || revision > 10000) throw new IllegalArgumentException();
            sessionId = UUID.fromString(sessionId).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid training photo context");
        }
    }

    String prompt() {
        return "Учебный бизнес-ланч на двух гостей. " + STAGES.get(stageCode)
                + " Если деталь не видна, скажи об этом. Фото не подтверждает выполнение поручения.\n";
    }

    String signature() { return sessionId + ":" + scenarioCode + ":" + stageCode + ":" + revision; }
}
