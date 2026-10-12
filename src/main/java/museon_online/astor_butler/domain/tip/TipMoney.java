package museon_online.astor_butler.domain.tip;

import java.util.Locale;

public final class TipMoney {

    private TipMoney() {
    }

    /** Whole rubles of a sum in kopecks; thousands are set apart from five digits up, as Russian text does. */
    public static String rubles(long minor) {
        long rubles = minor / 100L;
        if (rubles < 10_000L) {
            return Long.toString(rubles);
        }
        return String.format(Locale.ROOT, "%,d", rubles).replace(',', ' ');
    }
}
