package museon_online.astor_butler.api.glasses;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the earpiece may say. The manager's rule for Astor Glass (interview of 2026-10-10, question 35;
 * issue #135): no swearing and no sums in the earpiece, names are fine. The glasses are worn next to
 * guests, so whatever Astor says aloud is heard by them too.
 *
 * This filter is applied to everything that goes to speech — the assist answer, the task confirmation,
 * a staff message read through {@code /api/glasses/speech} — and never to the text itself: the phone
 * screen, the system chat and the shift feed keep the full answer, prices included.
 *
 * Three rules, each on its own:
 * <ul>
 *   <li><b>Swearing</b>: a conservative Russian and English lexicon ({@code glasses/speech-filter-words.txt},
 *       plus {@code ASTOR_GLASSES_SPEECH_FILTER_EXTRA_WORDS}). Common obfuscations are recognised — an
 *       asterisk inside the word, digits and Latin look-alikes in a Cyrillic word. One hit removes the
 *       word; two or more hits in one sentence remove the sentence.</li>
 *   <li><b>Sums and prices</b>: a number with a currency («1 500 ₽», «1500 руб.», «1.5 тыс. рублей»,
 *       «$20», «полторы тысячи рублей») or after a price word («стоит 450», «цена 1200») is replaced with
 *       «цена в меню». Other numbers — tables, times, party sizes, minutes — are left as they are.</li>
 *   <li><b>Names</b>: the filter cannot tell a name from any other word, so names in an answer are
 *       voiced as they are; the task confirmation is the one place a name is placed deliberately, and
 *       {@code ASTOR_GLASSES_SPEAK_NAMES=false} leaves it out there (see {@link #speakNames()}).</li>
 * </ul>
 * Remarks about staff cannot be filtered automatically either; the prompts ask the model not to put
 * them into the spoken part. {@code ASTOR_GLASSES_SPEECH_FILTER_ENABLED=false} passes everything through.
 * The filter is pure and idempotent: filtering its own output changes nothing.
 */
@Component
public class GlassesSpeechFilter {
    static final String WORDS_RESOURCE = "/glasses/speech-filter-words.txt";
    /** What is said instead of an amount. */
    static final String PRICE = "цена в меню";
    /** What is said when the filter left nothing of the answer; the text is still on the screen. */
    static final String NOTHING_LEFT = "Ответ — на экране.";

    // A sentence ends at «!», «?», «…», a line break, or a full stop that is followed by a capital, a digit or the
    // end: «1.5 тыс. рублей» and «т.е.» stay in one sentence. The break keeps the whitespace after it.
    private static final Pattern BREAK = Pattern.compile("(?:[!?…]+(?=\\s|$)|\\.+(?=\\s+[\\p{Lu}\\p{Nd}«\"(]|\\s*$)|\\n)\\s*");
    private static final Pattern TAIL = Pattern.compile("[.!?…\\s]*$");
    private static final Pattern LEADING = Pattern.compile("^[\\s,;:—–\\-]+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{Nd}*]+");

    private static final String NUMBER = "(?:\\d{1,3}(?:[ \\u00A0\\u202F\\u2009]\\d{3})+|\\d+)(?:[.,]\\d+)?";
    private static final String RANGE = NUMBER + "(?:\\s*(?:[-–—]|до)\\s*" + NUMBER + ")?";
    private static final String SCALE = "(?:\\s*(?:тыс\\.?|тысяч[иау]?|к|млн\\.?|миллион(?:а|ов)?))?";
    private static final String WORD_NUMBER = "(?:(?:одн(?:а|у|ой|ого|им)|один|дв(?:а|е|ух|ум|умя)|три|тр[её]х|четыр(?:е|ёх|ех)"
            + "|пять|пяти|шесть|шести|семь|семи|восемь|восьми|девять|девяти|десять|десяти|одиннадцать|двенадцать"
            + "|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать|двадцать|двадцати"
            + "|тридцать|тридцати|сорок|сорока|пятьдесят|пятидесяти|шестьдесят|семьдесят|восемьдесят|девяносто|сто|ста"
            + "|двести|двухсот|триста|четыреста|пятьсот|шестьсот|семьсот|восемьсот|девятьсот|тысяч[аиуе]?|полтор(?:а|ы))\\s*){1,4}";
    private static final String CURRENCY = "(?:рубл(?:ей|я|ь|и|ях)|руб\\.?|р\\.|р(?![\\p{L}])|₽|rub(?![\\p{L}])"
            + "|долл(?:аров|ара|ар)?(?![\\p{L}])|usd(?![\\p{L}])|\\$|евро|eur(?![\\p{L}])|€|фунт(?:ов|а)?|£)";
    private static final String SYMBOL = "(?:[₽$€£]|usd|eur|rub)";
    private static final String PRICE_WORD = "(?:стоит|стоят|стоил[аои]?|стоимость(?:ю)?|цен(?:а|ы|ой|е|у)|по цене|ценник|сумм[аеу]|итого)";
    private static final String LEAD_WORD = "(?:за|на|в|по|всего|около|примерно|порядка|в районе|от|до|плюс|минус|ещё|где-то)";
    // «стоит», «цена — от», «за», «:» or a dash before the number; each word starts at a word boundary.
    private static final String LEAD = "(?:(?<![\\p{L}])" + PRICE_WORD + ")?\\s*(?:(?<![\\p{L}])" + LEAD_WORD + "\\s+|[—–\\-:]\\s*)*";
    private static final String PER = "(?:\\s*(?:за|/|на)\\s*(?:порци[юя]|персон[уа]|человек[а]?|чел\\.?|шт\\.?|штук[уа]?|гост[ья]|блюдо|ед\\.?))?";
    // After a price word a bare number is a price unless it is plainly something else: a time, a percentage,
    // guests, tables, minutes, grams.
    private static final String NOT_A_PRICE = "(?![:.,]?\\d)(?!\\s*(?:%|проц|гост|чел|персон|мест|стол|минут|мин(?![\\p{L}])|час"
            + "|порц|грамм|гр(?![\\p{L}])|г(?![\\p{L}])|мл|кг|литр|л(?![\\p{L}])|шт|человек))";
    private static final Pattern AMOUNT = Pattern.compile("(?iu)"
            // 1 500 ₽, 1500 руб., 1.5 тыс. рублей, от 450 до 600 рублей, полторы тысячи рублей, 20$
            + "(?:" + LEAD + "(?:" + RANGE + SCALE + "|(?<![\\p{L}])" + WORD_NUMBER + ")\\s*" + CURRENCY + PER + ")"
            // ₽ 1500, $20, USD 20
            + "|(?:" + LEAD + "(?<![\\p{L}])" + SYMBOL + "\\s*" + RANGE + SCALE + PER + ")"
            // стоит 450, цена — 1200, стоимость от 450 до 600
            + "|(?:(?<![\\p{L}])" + PRICE_WORD + "\\s*(?:(?<![\\p{L}])" + LEAD_WORD + "\\s+|[—–\\-:]\\s*)*" + RANGE + SCALE
            + "(?:\\s*" + CURRENCY + ")?" + NOT_A_PRICE + PER + ")");

    private final boolean enabled;
    private final boolean speakNames;
    private final List<Entry> lexicon;

    /** One lexicon line: the stem and whether any ending may follow it. */
    record Entry(String stem, boolean prefix) {
        static Entry of(String word) {
            String normalized = normalize(word.strip().toLowerCase(Locale.ROOT));
            boolean prefix = normalized.endsWith("*");
            return new Entry(prefix ? normalized.substring(0, normalized.length() - 1) : normalized, prefix);
        }
    }

    @Autowired
    public GlassesSpeechFilter(@Value("${astor.glasses.speech-filter-enabled:true}") boolean enabled,
                               @Value("${astor.glasses.speak-names:true}") boolean speakNames,
                               @Value("${astor.glasses.speech-filter-extra-words:}") String extraWords) {
        this.enabled = enabled;
        this.speakNames = speakNames;
        List<Entry> entries = new ArrayList<>(bundled());
        for (String word : (extraWords == null ? "" : extraWords).split(",")) {
            if (!word.isBlank()) entries.add(Entry.of(word));
        }
        this.lexicon = List.copyOf(entries);
    }

    /** Everything on, the bundled lexicon only: the production default. */
    public static GlassesSpeechFilter defaults() { return new GlassesSpeechFilter(true, true, ""); }

    /** Everything through, as with {@code ASTOR_GLASSES_SPEECH_FILTER_ENABLED=false}. */
    public static GlassesSpeechFilter disabled() { return new GlassesSpeechFilter(false, true, ""); }

    boolean enabled() { return enabled; }

    /** Whether a task confirmation names the assignee ({@code ASTOR_GLASSES_SPEAK_NAMES}, default true). */
    public boolean speakNames() { return speakNames; }

    /** What the earpiece says for this text: the text itself when the filter is off or finds nothing. */
    public String spoken(String text) {
        if (text == null || !enabled) return text;
        StringBuilder out = new StringBuilder();
        Matcher breaks = BREAK.matcher(text);
        int start = 0;
        while (breaks.find()) {
            if (breaks.end() == start) continue;
            String sentence = sentence(text.substring(start, breaks.end()));
            if (sentence != null) out.append(sentence);
            start = breaks.end();
        }
        if (start < text.length()) {
            String sentence = sentence(text.substring(start));
            if (sentence != null) out.append(sentence);
        }
        String result = tidy(out.toString());
        return result.isEmpty() && !text.isBlank() ? NOTHING_LEFT : result;
    }

    /** One sentence without swearing and amounts, or null when nothing sayable is left of it. */
    private String sentence(String sentence) {
        // The full stop and the whitespace after it are kept aside, so «250 р.» loses the amount, not the stop.
        Matcher tail = TAIL.matcher(sentence);
        String end = tail.find() ? tail.group() : "";
        String body = sentence.substring(0, sentence.length() - end.length());
        List<int[]> hits = new ArrayList<>();
        Matcher words = WORD.matcher(body);
        while (words.find()) {
            if (profane(words.group())) hits.add(new int[]{words.start(), words.end()});
        }
        if (hits.size() > 1) return null;
        String result = body;
        if (hits.size() == 1) result = body.substring(0, hits.get(0)[0]) + body.substring(hits.get(0)[1]);
        result = AMOUNT.matcher(result).replaceAll(Matcher.quoteReplacement(" — " + PRICE + " "));
        if (!WORD.matcher(result).find()) return null;
        if (!result.equals(body)) {
            // Something was cut or replaced: the sentence still starts with a word, and that word with a capital.
            result = LEADING.matcher(result).replaceFirst("");
            if (!result.isEmpty() && Character.isLowerCase(result.charAt(0))) {
                result = Character.toUpperCase(result.charAt(0)) + result.substring(1);
            }
        }
        return result + end;
    }

    /* ---------- swearing ---------- */

    boolean profane(String token) {
        String lower = normalize(token.toLowerCase(Locale.ROOT));
        int letters = 0, stars = 0, latin = 0, cyrillic = 0, digits = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '*') stars++;
            else if (Character.isDigit(c)) digits++;
            else if (Character.isLetter(c)) {
                letters++;
                if (c >= 'a' && c <= 'z') latin++;
                else if (c >= 'а' && c <= 'я') cyrillic++;
            }
        }
        // Too short, or more hidden than shown: «**» is not a word.
        if (letters < 2 || stars > letters) return false;
        if (listed(lower)) return true;
        // Obfuscations only: a plain word was just checked, and a plain Latin word is never read as Cyrillic
        // («ebay» must not become «ебау»).
        if (stars == 0 && digits == 0 && (latin == 0 || cyrillic == 0)) return false;
        return listed(leet(lower)) || listed(cyrillic(lower));
    }

    private boolean listed(String candidate) {
        for (Entry entry : lexicon) {
            if (matches(entry, candidate)) return true;
        }
        return false;
    }

    /** An asterisk in the token stands for any one letter of the entry. */
    private static boolean matches(Entry entry, String token) {
        String stem = entry.stem();
        if (entry.prefix() ? token.length() < stem.length() : token.length() != stem.length()) return false;
        for (int i = 0; i < stem.length(); i++) {
            char c = token.charAt(i);
            if (c != '*' && c != stem.charAt(i)) return false;
        }
        return true;
    }

    private static String normalize(String word) {
        return word.replace('ё', 'е');
    }

    /** Digits and signs as Latin letters: «sh1t», «fu(k» stay out of scope, «f4ck», «$hit» come in. */
    private static String leet(String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            out.append(switch (c) {
                case '0' -> 'o'; case '1' -> 'i'; case '3' -> 'e'; case '4' -> 'a'; case '5' -> 's'; case '7' -> 't';
                case '8' -> 'b'; case '@' -> 'a'; case '$' -> 's';
                default -> c;
            });
        }
        return out.toString();
    }

    /** Latin look-alikes and digits as Cyrillic letters: «хyй» → «хуй», «пи3да» → «пизда», «бл9дь» stays out. */
    private static String cyrillic(String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            out.append(switch (c) {
                case 'a' -> 'а'; case 'b' -> 'б'; case 'c' -> 'с'; case 'd' -> 'д'; case 'e' -> 'е'; case 'f' -> 'ф';
                case 'g' -> 'г'; case 'h' -> 'н'; case 'i' -> 'и'; case 'j' -> 'й'; case 'k' -> 'к'; case 'l' -> 'л';
                case 'm' -> 'м'; case 'n' -> 'н'; case 'o' -> 'о'; case 'p' -> 'р'; case 'r' -> 'р'; case 's' -> 'с';
                case 't' -> 'т'; case 'u' -> 'у'; case 'v' -> 'в'; case 'w' -> 'ш'; case 'x' -> 'х'; case 'y' -> 'у';
                case 'z' -> 'з'; case '0' -> 'о'; case '3' -> 'з'; case '4' -> 'ч'; case '6' -> 'б'; case '@' -> 'а';
                default -> c;
            });
        }
        return out.toString();
    }

    /* ---------- tidying ---------- */

    private static String tidy(String text) {
        String s = text.replace(' ', ' ').replaceAll("[ \\t]{2,}", " ");
        s = s.replaceAll("(?:,\\s*){2,}", ", ");                       // «, ,» where a word was cut
        s = s.replaceAll(",\\s*([.!?…])", "$1");                        // «, .» at the end of a sentence
        s = s.replaceAll(" +([,.!?;:…)])", "$1");                       // no space before punctuation
        s = s.replaceAll("\\( +", "(");
        s = s.replaceAll("(?m)^[ ,;:]+", "");                          // a line never starts with punctuation
        s = s.replaceAll("(?:[—–\\-]\\s*){2,}", "— ");                  // «— — » when a dash was already there
        s = s.replaceAll(",(\\s*— " + PRICE + ")", "$1");               // «борщ, стоит 450» → «борщ — цена в меню»
        s = s.replaceAll("(^|[.!?…]\\s+)[—–\\-]\\s*", "$1");            // a sentence never starts with a dash
        s = s.replaceAll("(^|[.!?…]\\s+)ц(ена в меню)", "$1Ц$2");
        s = s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    /* ---------- lexicon ---------- */

    private static List<Entry> bundled() {
        List<Entry> entries = new ArrayList<>();
        try (InputStream in = GlassesSpeechFilter.class.getResourceAsStream(WORDS_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing " + WORDS_RESOURCE);
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip();
                if (word.isEmpty() || word.startsWith("#")) continue;
                entries.add(Entry.of(word));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + WORDS_RESOURCE, e);
        }
        return entries;
    }
}
