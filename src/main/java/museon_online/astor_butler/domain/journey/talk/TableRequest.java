package museon_online.astor_butler.domain.journey.talk;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** A guest's request at the table, said in their own words (spec 2026-10-13 §3.3). The bill has its own flow. */
public enum TableRequest {
    BREAD("хлеб", "(ещ[её]|можно|принесите|дайте|нам)\\s+(\\S+\\s+)?хлеб"),
    WATER("воду", "(можно|принесите|дайте|нам|ещ[её])\\s+(\\S+\\s+)?вод[уыа]"),
    WAITER("официанта", "позов[иы]|подойдите|официант[а]?\\s*(подойд|пожалуйста|$)|подойд[её]т\\s+официант"),
    SPEED_UP("ускорить подачу", "ускор|побыстрее|долго\\s+(ждём|ждем|нет)"),
    CUTLERY("приборы", "прибор|вилк[уи]|нож\\b|ложк[уи]|салфетк"),
    /** Packing what is left (or a dish) to take away; «у меня с собой торт» is not one. */
    TAKEAWAY("упаковать с собой", "упаку|упаков|запаку|заверн|на\\s*вынос|контейнер"
            + "|(можно|забрать|забер\\S*|взять|возьм\\S*|остатки|остальное)\\s+(\\S+\\s+){0,3}?с\\s+собой");

    private final String what;
    private final Pattern pattern;

    TableRequest(String what, String regex) {
        this.what = what;
        this.pattern = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** What the staff card says the guest asks for. */
    public String what() {
        return what;
    }

    public static Optional<TableRequest> recognise(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String said = text.toLowerCase(Locale.ROOT).trim();
        if (said.startsWith("без ")) {
            return Optional.empty();
        }
        for (TableRequest request : values()) {
            if (request.pattern.matcher(said).find()) {
                return Optional.of(request);
            }
        }
        return Optional.empty();
    }
}
