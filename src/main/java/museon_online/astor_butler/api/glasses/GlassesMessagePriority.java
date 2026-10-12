package museon_online.astor_butler.api.glasses;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a message to the staff member may be spoken (issue #186). The trainee wearing the glasses
 * (interview of 2026-10-12, question 7) moved the question from «what» to «when»: the guest does not
 * hear the earpiece, so an ordinary message should wait for a pause in the conversation, and only an
 * alert may interrupt it. The hostess (question 21) asked for the same channel not to become a stream
 * of repeated announcements.
 *
 * This class holds the rules; the outbox in {@link GlassesMessagesController} applies them. Everything is
 * deterministic and runs without a model call:
 * <ul>
 *   <li><b>Priority</b>: {@code ALERT} when the sender says so ({@code priority: ALERT}, or the task
 *       priority {@code HIGH} passed through from a staff task), when the source is a manager escalation
 *       or the shift kill switch, or when the text carries an alert word («аллерг…», «VIP», «срочно»,
 *       «задерж…», «стоп» — {@code ASTOR_GLASSES_ALERT_WORDS}); everything else is {@code NORMAL}. The
 *       sender cannot lower a priority the words have raised.</li>
 *   <li><b>Rate</b>: at most {@code ASTOR_GLASSES_MESSAGES_PER_MINUTE} ordinary messages are spoken per
 *       wearer and minute; the rest stay on the screen and are announced once as
 *       «Ещё N сообщений — на экране». An alert is never held back or folded into that line.</li>
 *   <li><b>Repeats</b>: the same text (case, spacing and «ё» aside) within two minutes is one message.</li>
 * </ul>
 * All of it is behind {@code ASTOR_GLASSES_MESSAGE_PRIORITY_ENABLED}; off, the queue is what it was.
 */
@Component
public class GlassesMessagePriority {
    public static final String ALERT = "ALERT";
    public static final String NORMAL = "NORMAL";
    /** The staff task priority a task source passes through; in the earpiece it means an alert. */
    static final String HIGH = "HIGH";
    /** Who put the message into the queue. Escalations and the kill switch are alerts by themselves. */
    static final String SOURCE_RESTAURANT = "RESTAURANT";
    static final String SOURCE_STAFF_TASK = "STAFF_TASK";
    static final String SOURCE_ESCALATION = "ESCALATION";
    static final String SOURCE_KILL_SWITCH = "KILL_SWITCH";
    static final Set<String> SOURCES = Set.of(SOURCE_RESTAURANT, SOURCE_STAFF_TASK, SOURCE_ESCALATION, SOURCE_KILL_SWITCH);
    static final Set<String> ALERT_SOURCES = Set.of(SOURCE_ESCALATION, SOURCE_KILL_SWITCH);
    /** A trailing «*» matches any ending: «аллерг*» is «аллергия», «аллергик», «аллергию»; «стоп» is the word only. */
    static final String DEFAULT_ALERT_WORDS = "аллерг*,vip,вип,срочно,задерж*,стоп";
    static final int DEFAULT_PER_MINUTE = 6;
    static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{Nd}]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING = Pattern.compile("[\\s.!?…]+$");

    private record Entry(String stem, boolean prefix) { }

    private final boolean enabled;
    private final int perMinute;
    private final List<Entry> words;

    @Autowired
    public GlassesMessagePriority(@Value("${astor.glasses.message-priority-enabled:false}") boolean enabled,
                                  @Value("${astor.glasses.messages-per-minute:" + DEFAULT_PER_MINUTE + "}") int perMinute,
                                  @Value("${astor.glasses.alert-words:" + DEFAULT_ALERT_WORDS + "}") String alertWords) {
        this.enabled = enabled;
        this.perMinute = Math.max(1, perMinute);
        this.words = parse(alertWords);
    }

    /** The queue as it was before #186: no priority, no rate, no repeat check. */
    public static GlassesMessagePriority disabled() {
        return new GlassesMessagePriority(false, DEFAULT_PER_MINUTE, DEFAULT_ALERT_WORDS);
    }

    static GlassesMessagePriority enabled(int perMinute) {
        return new GlassesMessagePriority(true, perMinute, DEFAULT_ALERT_WORDS);
    }

    public boolean enabled() { return enabled; }

    /** Ordinary messages spoken per wearer and minute; the rest are announced as one line. */
    public int perMinute() { return perMinute; }

    /** {@link #ALERT} or {@link #NORMAL} for this text from this source, given what the sender asked for. */
    public String classify(String text, String source, String requested) {
        if (ALERT.equals(requested) || HIGH.equals(requested)) return ALERT;
        if (source != null && ALERT_SOURCES.contains(source)) return ALERT;
        return alertWord(text) ? ALERT : NORMAL;
    }

    /** Whether one of the alert words is in the text: whole words, case and «ё» do not matter. */
    public boolean alertWord(String text) {
        if (text == null) return false;
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = fold(matcher.group());
            for (Entry entry : words) {
                if (entry.prefix() ? word.startsWith(entry.stem()) : word.equals(entry.stem())) return true;
            }
        }
        return false;
    }

    /** The canonical source name, {@link #SOURCE_RESTAURANT} when the sender named none, {@code null} when unknown. */
    static String source(String value) {
        if (value == null) return SOURCE_RESTAURANT;
        String name = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return SOURCES.contains(name) ? name : null;
    }

    /** The canonical requested priority, {@code null} when the sender asked for nothing or for something unknown. */
    static String requested(String value) {
        if (value == null) return null;
        String name = value.strip().toUpperCase(Locale.ROOT);
        return switch (name) {
            case ALERT, NORMAL, HIGH -> name;
            default -> null;
        };
    }

    /** The key under which a repeated text is the same message: case, spacing, «ё» and a final full stop do not count. */
    static String duplicateKey(String text) {
        String folded = SPACE.matcher(fold(text)).replaceAll(" ").strip();
        return TRAILING.matcher(folded).replaceAll("");
    }

    /** «Ещё 3 сообщения — на экране»: what the earpiece says instead of the messages held by the rate. */
    static String summary(int count) {
        return "Ещё " + count + " " + plural(count, "сообщение", "сообщения", "сообщений") + " — на экране";
    }

    static String plural(int count, String one, String few, String many) {
        int tens = count % 100;
        int ones = count % 10;
        if (tens >= 11 && tens <= 14) return many;
        if (ones == 1) return one;
        if (ones >= 2 && ones <= 4) return few;
        return many;
    }

    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static List<Entry> parse(String configured) {
        List<Entry> entries = new ArrayList<>();
        if (configured == null) return entries;
        for (String raw : configured.split(",")) {
            String word = fold(raw.strip());
            boolean prefix = word.endsWith("*");
            if (prefix) word = word.substring(0, word.length() - 1);
            if (word.isEmpty()) continue;
            entries.add(new Entry(word, prefix));
        }
        return entries;
    }
}
