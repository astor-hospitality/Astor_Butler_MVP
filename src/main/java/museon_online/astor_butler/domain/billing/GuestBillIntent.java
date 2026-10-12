package museon_online.astor_butler.domain.billing;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a guest's words ask for the bill (#153 A2). Deterministic and narrow on purpose: whole words only, so that
 * «счётчик» or «чековая» do not ask for anything, and no model is consulted. «ё» and «е» are the same letter here.
 */
public final class GuestBillIntent {

    /**
     * The words the waiter and the hostess named: «счёт» / «счет» (so also «можно счёт»), «чек», «посчитайте»,
     * «рассчитайте», «оплатить», «закрыть стол». Matched on the lower-cased text with «ё» folded to «е».
     */
    static final Pattern BILL_WORDS = Pattern.compile(
            "\\b(?:счет|чек|посчитайте|рассчитайте|оплатить|закрыть\\s+стол)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);

    private GuestBillIntent() {
    }

    public static boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return BILL_WORDS.matcher(normalize(text)).find();
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }
}
