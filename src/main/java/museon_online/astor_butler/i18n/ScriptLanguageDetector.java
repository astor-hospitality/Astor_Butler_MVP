package museon_online.astor_butler.i18n;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free, deterministic language detection from the alphabet a message is written in. No network, no model:
 * Unicode scripts, a handful of letters that only one language uses, and a short list of English function words.
 *
 * <p>It is sure about Russian, the other Cyrillic languages with their own letters (uk, be, kk, tg, tt, ba, sr, mk),
 * and every language with an alphabet of its own (el, hy, ka, he, ar/fa/ur, hi, th, ko, ja, zh, ...). For Latin
 * script it recognises a few languages by their diacritics and English by its function words, and otherwise says
 * nothing: telling Spanish from Italian is the job of a paid detector (Yandex Translate {@code detectLanguage}),
 * which can sit behind this one in a later phase.
 */
public final class ScriptLanguageDetector implements LanguageDetector {

    public static final String METHOD = "script";

    private static final double DOMINANT_SHARE = 0.6;
    private static final int OWN_SCRIPT_MIN_LETTERS = 2;
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern MENTION = Pattern.compile("[@#/][\\p{L}\\p{N}_]+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    /** Scripts used by more than one language: the first entry is the guess, a hint from the list wins over it. */
    private static final Map<UnicodeScript, List<String>> SCRIPT_LANGUAGES = scriptLanguages();
    /** Cyrillic languages a plain Russian-alphabet text may still be written in. */
    private static final Set<String> AMBIGUOUS_CYRILLIC = Set.of("bg", "mk", "sr", "mn");
    private static final Set<String> TURKIC_CYRILLIC = Set.of("ky", "mn", "kk", "tt", "ba");
    private static final Set<String> ENGLISH_WORDS = Set.of(
            "the", "a", "an", "and", "or", "is", "are", "am", "be", "to", "for", "of", "in", "on", "at", "with",
            "from", "by", "i", "i'm", "i'd", "we", "you", "my", "our", "your", "me", "us", "it", "this", "that",
            "there", "here", "please", "can", "could", "would", "will", "want", "like", "need", "have", "has", "do",
            "does", "what", "when", "where", "how", "table", "book", "booking", "reservation", "menu", "tonight",
            "today", "tomorrow", "people", "thanks", "thank", "hello", "hi", "good", "evening", "morning", "dinner",
            "lunch"
    );

    private final int minLetters;

    /** @param minLetters Latin/Cyrillic messages with fewer letters say too little to be trusted */
    public ScriptLanguageDetector(int minLetters) {
        this.minLetters = Math.max(1, minLetters);
    }

    @Override
    public Optional<DetectedLanguage> detect(String text, List<String> hints) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = MENTION.matcher(URL.matcher(text).replaceAll(" ")).replaceAll(" ");
        Map<UnicodeScript, Integer> counts = new EnumMap<>(UnicodeScript.class);
        int letters = 0;
        int kana = 0;
        for (int i = 0; i < cleaned.length(); ) {
            int codePoint = cleaned.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            UnicodeScript script = UnicodeScript.of(codePoint);
            if (script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA) {
                kana++;
                script = UnicodeScript.HAN;
            }
            counts.merge(script, 1, Integer::sum);
            letters++;
        }
        if (letters == 0) {
            return Optional.empty();
        }
        Map.Entry<UnicodeScript, Integer> dominant = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        if (dominant.getValue() < DOMINANT_SHARE * letters) {
            return Optional.empty();
        }
        UnicodeScript script = dominant.getKey();
        boolean sharedAlphabet = script == UnicodeScript.LATIN || script == UnicodeScript.CYRILLIC;
        if (dominant.getValue() < (sharedAlphabet ? minLetters : OWN_SCRIPT_MIN_LETTERS)) {
            return Optional.empty();
        }
        List<String> normalizedHints = normalizedHints(hints);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        return switch (script) {
            case CYRILLIC -> Optional.of(cyrillic(lower, normalizedHints));
            case LATIN -> latin(lower);
            case HAN -> Optional.of(kana > 0
                    ? detected("ja", 0.95)
                    : normalizedHints.contains("ja") ? detected("ja", 0.8) : detected("zh", 0.85));
            case ARABIC -> Optional.of(arabic(cleaned, normalizedHints));
            default -> sharedScript(script, normalizedHints);
        };
    }

    private DetectedLanguage cyrillic(String text, List<String> hints) {
        if (containsAny(text, "ҷҳӣӯ")) {
            return detected("tg", 0.85);
        }
        if (containsAny(text, "җ")) {
            return detected("tt", 0.8);
        }
        if (containsAny(text, "ҙҫҡ")) {
            return detected("ba", 0.8);
        }
        if (containsAny(text, "әқұһ")) {
            return detected("kk", 0.8);
        }
        if (containsAny(text, "ђћџ")) {
            return detected("sr", 0.9);
        }
        if (containsAny(text, "ѓќѕ")) {
            return detected("mk", 0.9);
        }
        if (containsAny(text, "јљњ")) {
            return detected(firstHintIn(hints, Set.of("sr", "mk"), "sr"), 0.75);
        }
        if (containsAny(text, "їєґ")) {
            return detected("uk", 0.9);
        }
        if (containsAny(text, "ў")) {
            return detected("be", 0.9);
        }
        if (containsAny(text, "ңөү")) {
            String hint = firstHintIn(hints, TURKIC_CYRILLIC, "");
            return hint.isEmpty() ? detected("ky", 0.6) : detected(hint, 0.8);
        }
        if (containsAny(text, "і")) {
            return containsAny(text, "ы") ? detected("be", 0.7) : detected("uk", 0.8);
        }
        boolean ambiguous = hints.stream().anyMatch(AMBIGUOUS_CYRILLIC::contains);
        return detected("ru", ambiguous ? 0.5 : 0.9);
    }

    private Optional<DetectedLanguage> latin(String text) {
        if (containsAny(text, "ơưđ") || containsRange(text, 0x1EA0, 0x1EF9)) {
            return Optional.of(detected("vi", 0.9));
        }
        if (containsAny(text, "ə")) {
            return Optional.of(detected("az", 0.85));
        }
        if (containsAny(text, "ğı")) {
            return Optional.of(detected("tr", 0.85));
        }
        if (containsAny(text, "ß")) {
            return Optional.of(detected("de", 0.85));
        }
        if (containsAny(text, "ñ¿¡")) {
            return Optional.of(detected("es", 0.85));
        }
        if (containsAny(text, "ãõ")) {
            return Optional.of(detected("pt", 0.85));
        }
        if (containsAny(text, "łżśźń")) {
            return Optional.of(detected("pl", 0.85));
        }
        if (containsAny(text, "řůě")) {
            return Optional.of(detected("cs", 0.85));
        }
        if (containsAny(text, "őű")) {
            return Optional.of(detected("hu", 0.85));
        }
        if (containsAny(text, "șț")) {
            return Optional.of(detected("ro", 0.85));
        }
        if (containsAny(text, "œ")) {
            return Optional.of(detected("fr", 0.8));
        }
        return looksEnglish(text) ? Optional.of(detected("en", 0.8)) : Optional.empty();
    }

    private DetectedLanguage arabic(String text, List<String> hints) {
        // Urdu-only letters, then letters Persian (and Urdu, Pashto, Kurdish) add to the Arabic alphabet.
        if (containsAny(text, "\u0679\u0688\u0691\u06BA\u06D2")) {
            return detected("ur", 0.85);
        }
        if (containsAny(text, "\u067E\u0686\u0698\u06AF\u06CC\u06A9")) {
            return detected(firstHintIn(hints, Set.of("fa", "ur", "ps", "ckb", "ku"), "fa"), 0.8);
        }
        return detected("ar", 0.85);
    }

    private Optional<DetectedLanguage> sharedScript(UnicodeScript script, List<String> hints) {
        List<String> languages = SCRIPT_LANGUAGES.get(script);
        if (languages == null) {
            return Optional.empty();
        }
        if (languages.size() == 1) {
            return Optional.of(detected(languages.getFirst(), 0.95));
        }
        String hint = firstHintIn(hints, Set.copyOf(languages), "");
        return Optional.of(hint.isEmpty() ? detected(languages.getFirst(), 0.75) : detected(hint, 0.85));
    }

    private boolean looksEnglish(String text) {
        Matcher matcher = WORD.matcher(text);
        int words = 0;
        int hits = 0;
        while (matcher.find()) {
            words++;
            if (ENGLISH_WORDS.contains(matcher.group())) {
                hits++;
            }
        }
        return words >= 2 && hits >= 2 && hits >= 0.3 * words;
    }

    private static List<String> normalizedHints(List<String> hints) {
        if (hints == null || hints.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String hint : hints) {
            String normalized = LanguageTags.normalize(hint);
            if (!normalized.isEmpty() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String firstHintIn(List<String> hints, Set<String> allowed, String fallback) {
        for (String hint : hints) {
            if (allowed.contains(hint)) {
                return hint;
            }
        }
        return fallback;
    }

    private static boolean containsAny(String text, String characters) {
        for (int i = 0; i < characters.length(); i++) {
            if (text.indexOf(characters.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRange(String text, int from, int to) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= from && codePoint <= to);
    }

    private static DetectedLanguage detected(String language, double confidence) {
        return new DetectedLanguage(language, confidence, METHOD);
    }

    private static Map<UnicodeScript, List<String>> scriptLanguages() {
        Map<UnicodeScript, List<String>> map = new EnumMap<>(UnicodeScript.class);
        map.put(UnicodeScript.GREEK, List.of("el"));
        map.put(UnicodeScript.ARMENIAN, List.of("hy"));
        map.put(UnicodeScript.GEORGIAN, List.of("ka"));
        map.put(UnicodeScript.HEBREW, List.of("he", "yi"));
        map.put(UnicodeScript.DEVANAGARI, List.of("hi", "mr", "ne", "sa"));
        map.put(UnicodeScript.BENGALI, List.of("bn", "as"));
        map.put(UnicodeScript.GURMUKHI, List.of("pa"));
        map.put(UnicodeScript.GUJARATI, List.of("gu"));
        map.put(UnicodeScript.ORIYA, List.of("or"));
        map.put(UnicodeScript.TAMIL, List.of("ta"));
        map.put(UnicodeScript.TELUGU, List.of("te"));
        map.put(UnicodeScript.KANNADA, List.of("kn"));
        map.put(UnicodeScript.MALAYALAM, List.of("ml"));
        map.put(UnicodeScript.SINHALA, List.of("si"));
        map.put(UnicodeScript.THAI, List.of("th"));
        map.put(UnicodeScript.LAO, List.of("lo"));
        map.put(UnicodeScript.KHMER, List.of("km"));
        map.put(UnicodeScript.MYANMAR, List.of("my"));
        map.put(UnicodeScript.TIBETAN, List.of("bo"));
        map.put(UnicodeScript.ETHIOPIC, List.of("am", "ti"));
        map.put(UnicodeScript.MONGOLIAN, List.of("mn"));
        map.put(UnicodeScript.HANGUL, List.of("ko"));
        map.put(UnicodeScript.THAANA, List.of("dv"));
        return map;
    }
}
