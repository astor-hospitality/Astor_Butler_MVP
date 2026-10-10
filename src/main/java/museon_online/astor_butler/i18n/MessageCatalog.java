package museon_online.astor_butler.i18n;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-edited texts by language and key, read from {@code classpath:i18n/<bundle>/<language>.yaml}.
 *
 * <p>Nested YAML maps become dotted keys ({@code feedback: {ask_text: ...}} is {@code feedback.ask_text}). Values
 * are templates with named placeholders: {@code "Бронь #{orderId} подтверждена"}. Russian is the source of truth,
 * English is edited by people as well; other languages are machine translated at runtime (see {@link GuestTexts})
 * or added here as reviewed files later.
 */
@Slf4j
public final class MessageCatalog {

    public static final String GUEST_BUNDLE = "guest";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    private final Map<String, Map<String, String>> templates;

    private MessageCatalog(Map<String, Map<String, String>> templates) {
        this.templates = templates;
    }

    /**
     * @param templates language to (key to template); languages are normalized
     */
    public static MessageCatalog of(Map<String, Map<String, String>> templates) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        if (templates != null) {
            templates.forEach((language, entries) -> {
                String normalized = LanguageTags.normalize(language);
                if (!normalized.isEmpty() && entries != null) {
                    copy.put(normalized, Map.copyOf(entries));
                }
            });
        }
        return new MessageCatalog(Map.copyOf(copy));
    }

    /** Reads {@code i18n/<bundle>/<language>.yaml} for every language; a missing file is logged and skipped. */
    public static MessageCatalog load(String bundle, Collection<String> languages) {
        Map<String, Map<String, String>> templates = new LinkedHashMap<>();
        ClassLoader classLoader = MessageCatalog.class.getClassLoader();
        for (String language : languages == null ? List.<String>of() : languages) {
            String normalized = LanguageTags.normalize(language);
            if (normalized.isEmpty()) {
                continue;
            }
            String resource = "i18n/" + bundle + "/" + normalized + ".yaml";
            try (InputStream stream = classLoader.getResourceAsStream(resource)) {
                if (stream == null) {
                    log.warn("Message catalog {} has no file for language {}", bundle, normalized);
                    continue;
                }
                templates.put(normalized, read(stream));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Message catalog " + resource + " cannot be read: " + e.getMessage(), e);
            }
        }
        return of(templates);
    }

    public Optional<String> template(String language, String key) {
        Map<String, String> entries = templates.get(LanguageTags.normalize(language));
        if (entries == null || key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(key));
    }

    public Set<String> languages() {
        return templates.keySet();
    }

    public Set<String> keys(String language) {
        Map<String, String> entries = templates.get(LanguageTags.normalize(language));
        return entries == null ? Set.of() : entries.keySet();
    }

    /** The text of one key in every language; used to recognise a localized button label coming back as text. */
    public Set<String> allTemplates(String key) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, String> entries : templates.values()) {
            String value = entries.get(key);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    /** Replaces {@code {name}} with the argument's text; placeholders without an argument stay as they are. */
    public static String format(String template, Map<String, ?> args) {
        if (template == null) {
            return "";
        }
        if (args == null || args.isEmpty() || template.indexOf('{') < 0) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = args.get(matcher.group(1));
            String replacement = value == null ? matcher.group() : String.valueOf(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static Set<String> placeholders(String template) {
        Set<String> result = new LinkedHashSet<>();
        if (template == null) {
            return result;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /** Lower case, single spaces, {@code ё} as {@code е}: how a button label is compared with what came back. */
    public static String normalizeLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }

    private static Map<String, String> read(InputStream stream) throws IOException {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            Map<String, String> flat = new LinkedHashMap<>();
            if (root instanceof Map<?, ?> map) {
                flatten("", map, flat);
            } else if (root != null) {
                throw new IllegalStateException("top level must be a map");
            }
            return flat;
        }
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else if (value != null) {
                target.put(key, String.valueOf(value));
            }
        }
    }
}
