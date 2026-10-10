package museon_online.astor_butler.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One spelling for a language across Telegram, the web widget, detectors and catalogs: the lower-case primary
 * subtag of a BCP 47 tag ({@code "pt-BR"} and {@code "pt_br"} are both {@code "pt"}, {@code "zh-hans"} is
 * {@code "zh"}). Region and script are dropped on purpose; a provider adapter maps the code to its own list.
 */
public final class LanguageTags {

    private static final Pattern PRIMARY = Pattern.compile("[a-z]{2,3}");
    /** Deprecated ISO 639 codes some clients still send. */
    private static final Map<String, String> ALIASES = Map.of(
            "iw", "he",
            "in", "id",
            "ji", "yi",
            "jw", "jv",
            "mo", "ro"
    );
    /** "undetermined", "no linguistic content", "multiple", "uncoded": not a language a guest can be answered in. */
    private static final Set<String> NOT_A_LANGUAGE = Set.of("und", "zxx", "mul", "mis");

    private LanguageTags() {
    }

    /**
     * @param raw anything a client may send: {@code "en"}, {@code "en-US"}, {@code "pt_br"}, {@code " RU "}
     * @return the lower-case primary subtag, or an empty string when there is no usable language
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int dash = value.indexOf('-');
        String primary = dash < 0 ? value : value.substring(0, dash);
        if (!PRIMARY.matcher(primary).matches() || NOT_A_LANGUAGE.contains(primary)) {
            return "";
        }
        return ALIASES.getOrDefault(primary, primary);
    }

    public static boolean isValid(String raw) {
        return !normalize(raw).isEmpty();
    }

    /** English name of the language for prompts and staff texts ({@code "de"} is {@code "German"}). */
    public static String englishName(String language) {
        String tag = normalize(language);
        if (tag.isEmpty()) {
            return "";
        }
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name == null || name.isBlank() ? tag : name;
    }

    /** Russian name of the language for staff notifications ({@code "de"} is {@code "немецкий"}). */
    public static String russianName(String language) {
        String tag = normalize(language);
        if (tag.isEmpty()) {
            return "";
        }
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.forLanguageTag("ru"));
        return name == null || name.isBlank() ? tag : name;
    }
}
