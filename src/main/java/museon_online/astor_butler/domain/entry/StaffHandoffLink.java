package museon_online.astor_butler.domain.entry;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The staff member in a guest's start link (#128). Two spellings, one meaning:
 * <ul>
 * <li>the business lunch link with two more fields — {@code https://t.me/<bot>?start=lunch_aeris…_h<staff_id>[_n<стол>]},
 * which reaches the bot as {@code /start lunch_aeris_hanna_n5}. {@code _t} in that grammar is the time
 * ({@code _t1300}, {@link museon_online.astor_butler.fsm.scenario.BusinessLunchHandoff}), so the table is {@code _n}
 * («номер стола»);</li>
 * <li>a plain entry without the lunch — {@code ?start=h_<staff_id>[_t<стол>]}, read as a bare {@code /start}.</li>
 * </ul>
 * The host stand QR is the same lunch link with the pseudo id {@link #HOST_STAND}. A link without {@code _h} is not a
 * handoff and reads as before. The lunch parser ignores these fields, so with the feature off nothing changes.
 * <p>Telegram allows up to {@value #TELEGRAM_PARAMETER_LIMIT} characters of {@code A-Za-z0-9_-} in {@code start};
 * {@code _} separates the fields, so a staff id or table code is letters, digits and {@code -} only.
 */
public record StaffHandoffLink(String staffId, String tableCode, boolean lunch) {

    public static final String HOST_STAND = "host";
    public static final int TELEGRAM_PARAMETER_LIMIT = 64;

    private static final Pattern START = Pattern.compile("^/start(?:@\\w+)?\\s+([A-Za-z0-9_-]{1,64})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN = Pattern.compile("^h_([A-Za-z0-9-]+)(?:_t([A-Za-z0-9-]+))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LUNCH = Pattern.compile("^lunch(?:[_-]([A-Za-z0-9_-]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LUNCH_FIELD = Pattern.compile("^([hn])([A-Za-z0-9-]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    /** The staff member and table in a {@code /start} text; empty for any other text, a bare {@code /start} included. */
    public static Optional<StaffHandoffLink> parse(String text) {
        Matcher start = START.matcher(text == null ? "" : text.trim());
        if (!start.matches()) {
            return Optional.empty();
        }
        String parameter = start.group(1);
        Matcher plain = PLAIN.matcher(parameter);
        if (plain.matches()) {
            return Optional.of(new StaffHandoffLink(plain.group(1), plain.group(2), false));
        }
        Matcher lunch = LUNCH.matcher(parameter);
        if (!lunch.matches() || lunch.group(1) == null) {
            return Optional.empty();
        }
        String staff = null;
        String table = null;
        String[] parts = lunch.group(1).split("_");
        // The first part is the venue, as the lunch parser reads it; a venue called "nova" is not a table.
        for (int i = 1; i < parts.length; i++) {
            Matcher field = LUNCH_FIELD.matcher(parts[i]);
            if (!field.matches()) {
                continue;
            }
            if ("h".equalsIgnoreCase(field.group(1))) {
                staff = staff == null ? field.group(2) : staff;
            } else {
                table = table == null ? field.group(2) : table;
            }
        }
        return staff == null ? Optional.empty() : Optional.of(new StaffHandoffLink(staff, table, true));
    }

    /** True for {@code /start h_<staff_id>[_t<стол>]}: a start without the lunch, which the first touch treats as {@code /start}. */
    public static boolean isPlainStart(String text) {
        return parse(text).filter(link -> !link.lunch()).isPresent();
    }

    /** A staff id or table code that fits into the link: letters, digits and {@code -}, nothing that could be a separator. */
    public static boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    public boolean hostStand() {
        return HOST_STAND.equalsIgnoreCase(staffId);
    }

    /** {@code lunch_aeris_hanna_n5}: the lunch deep-link with the staff member and, optionally, the table. */
    public static String lunchStartParameter(String venueCode, String staffId, String tableCode) {
        String venue = venueCode == null || venueCode.isBlank() ? "aeris" : venueCode.trim().toLowerCase(Locale.ROOT);
        return "lunch_" + venue + "_h" + staffId + (tableCode == null || tableCode.isBlank() ? "" : "_n" + tableCode);
    }

    /** {@code h_anna_t5}: the entry without the lunch. */
    public static String plainStartParameter(String staffId, String tableCode) {
        return "h_" + staffId + (tableCode == null || tableCode.isBlank() ? "" : "_t" + tableCode);
    }

    public static String url(String botUsername, String startParameter) {
        String bot = botUsername == null ? "" : botUsername.trim().replaceFirst("^@", "");
        return "https://t.me/" + bot + "?start=" + startParameter;
    }
}
