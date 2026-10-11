package museon_online.astor_butler.fsm.understanding;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A percent the way a guest writes or taps it: "10%", "15 %", "20 процентов", or a lone "20" typed in answer to a
 * question about percent. Whole numbers only: "17,5%" is not read, so the guest is asked again rather than guessed at.
 * Whether a lone number is a percent at all is the caller's to decide; this only tells it apart from a marked one.
 */
public final class GuestPercentText {

    public enum Kind {
        /** A number with "%" or "процент" after it. */
        MARKED,
        /** The whole text is a number and nothing else. */
        BARE,
        NONE
    }

    /** @param percent the number when {@code kind} is not {@code NONE}, otherwise zero */
    public record Reading(Kind kind, int percent) {

        private static final Reading NONE = new Reading(Kind.NONE, 0);

        public boolean found() {
            return kind != Kind.NONE;
        }
    }

    // Not in the middle of a word or of another number, so "1000%" and the "5%" of "17,5%" are not read.
    private static final Pattern MARKED = Pattern.compile("(?<![\\p{L}\\d.,])(\\d{1,3})\\s*(?:%|процент\\p{L}*)");
    private static final Pattern BARE = Pattern.compile("^(\\d{1,3})$");

    private GuestPercentText() {
    }

    public static Reading read(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        Matcher marked = MARKED.matcher(normalized);
        if (marked.find()) {
            return new Reading(Kind.MARKED, Integer.parseInt(marked.group(1)));
        }
        Matcher bare = BARE.matcher(normalized);
        if (bare.matches()) {
            return new Reading(Kind.BARE, Integer.parseInt(bare.group(1)));
        }
        return Reading.NONE;
    }
}
