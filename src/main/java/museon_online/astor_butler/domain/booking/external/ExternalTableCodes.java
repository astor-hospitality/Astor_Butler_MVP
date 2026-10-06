package museon_online.astor_butler.domain.booking.external;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One comparison key for a table, whether it is written as a Butler table code ("5", "BAR")
 * or as a table name in the restaurant system ("Стол 5", "VIP 13", "05").
 */
public final class ExternalTableCodes {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{Nd}]");

    private ExternalTableCodes() {
    }

    /**
     * A name with exactly one number is keyed by that number without leading zeros; anything
     * else is keyed by its letters and digits in upper case. Blank input gives an empty key.
     */
    public static String key(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = DIGITS.matcher(upper);
        if (matcher.find()) {
            String number = matcher.group();
            if (!matcher.find()) {
                return number.replaceFirst("^0+(?=\\d)", "");
            }
        }
        return NOT_ALPHANUMERIC.matcher(upper).replaceAll("");
    }
}
