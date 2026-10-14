package museon_online.astor_butler.domain.ledger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks Russian phone numbers in free text: {@code 8 (912) 000-00-60} becomes {@code +7 912 ***-**-60}. */
public final class PhoneMask {

    private static final String SEP = "[\\s\\-()]{0,2}";
    private static final Pattern PHONE = Pattern.compile("(?<![\\d+])(?:\\+7|7|8)" + SEP + "(\\d{3})" + SEP
            + "\\d{3}" + SEP + "\\d{2}" + SEP + "(\\d{2})(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS);

    private PhoneMask() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PHONE.matcher(text);
        return matcher.replaceAll(match -> Matcher.quoteReplacement("+7 " + match.group(1) + " ***-**-" + match.group(2)));
    }
}
