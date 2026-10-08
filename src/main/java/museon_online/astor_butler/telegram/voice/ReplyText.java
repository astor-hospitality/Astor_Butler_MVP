package museon_online.astor_butler.telegram.voice;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text helpers behind the voice reply: what the model answered is HTML or Markdown-ish prose with links;
 * the guest needs it three ways — plain lines for the summary, labelled links for buttons, and clean speech
 * without URLs, tags or emoji, cut into pieces one voice note long. No I/O, no Spring, fully deterministic.
 */
public final class ReplyText {

    private static final Pattern ANCHOR = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s<>\"')\\]]+");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_MARKS = Pattern.compile("(\\*\\*|__|`{1,3}|~~|^#{1,6}\\s+|^\\s*[-*•]\\s+)", Pattern.MULTILINE);
    private static final Pattern EMOJI = Pattern.compile("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");
    private static final Pattern BLANK_RUN = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern LINE_RUN = Pattern.compile("\\n{3,}");
    private static final List<String> IMAGE_SUFFIXES = List.of(".jpg", ".jpeg", ".png", ".webp");

    private ReplyText() {
    }

    /** A link as the guest should see it: the anchor text (or the host when there is none) and the URL. */
    public record Link(String label, String url) {
        public boolean image() {
            String path;
            try {
                path = URI.create(url).getPath();
            } catch (RuntimeException e) {
                return false;
            }
            if (path == null) {
                return false;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            return IMAGE_SUFFIXES.stream().anyMatch(lower::endsWith);
        }
    }

    /** Links in document order, deduplicated by URL: {@code <a href>}, Markdown links, then bare URLs. */
    public static List<Link> links(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Link> byUrl = new LinkedHashMap<>();
        Matcher anchors = ANCHOR.matcher(text);
        while (anchors.find()) {
            put(byUrl, anchors.group(1), plain(anchors.group(2)));
        }
        Matcher markdown = MARKDOWN_LINK.matcher(text);
        while (markdown.find()) {
            put(byUrl, markdown.group(2), markdown.group(1));
        }
        Matcher bare = BARE_URL.matcher(text);
        while (bare.find()) {
            put(byUrl, trimPunctuation(bare.group()), null);
        }
        return List.copyOf(byUrl.values());
    }

    private static void put(Map<String, Link> byUrl, String url, String label) {
        String key = url == null ? "" : url.trim();
        if (key.isBlank() || !key.toLowerCase(Locale.ROOT).startsWith("http") || byUrl.containsKey(key)) {
            return;
        }
        String shown = label == null || label.isBlank() || label.startsWith("http") ? host(key) : label.trim();
        byUrl.put(key, new Link(shown.length() > 40 ? shown.substring(0, 39) + "…" : shown, key));
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String trimPunctuation(String url) {
        String trimmed = url;
        while (!trimmed.isEmpty() && ".,;:!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Readable plain text: tags and Markdown marks gone, anchors replaced by their text, links kept as URLs. */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        String out = ANCHOR.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(2).isBlank()
                ? m.group(1) : m.group(2) + " " + m.group(1)));
        out = MARKDOWN_LINK.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + m.group(2)));
        out = out.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        out = TAG.matcher(out).replaceAll("");
        out = MARKDOWN_MARKS.matcher(out).replaceAll("");
        out = out.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        return tidy(out);
    }

    /** What the voice says: plain text without URLs or emoji; a sentence that was only a link disappears. */
    public static String speech(String text) {
        String out = plain(text);
        out = BARE_URL.matcher(out).replaceAll("");
        out = EMOJI.matcher(out).replaceAll("");
        out = out.replaceAll("[ \\t]*\\n[ \\t]*", "\n");
        return tidy(out);
    }

    /**
     * Cuts speech into voice-note sized pieces at sentence or line ends, never inside a word, and stops at
     * {@code maxTotalChars}: the rest is what "Подробнее" is for.
     */
    public static List<String> speechChunks(String speech, int chunkChars, int maxTotalChars) {
        if (speech == null || speech.isBlank() || chunkChars <= 0) {
            return List.of();
        }
        String budgeted = speech.length() <= maxTotalChars ? speech : cutAtBoundary(speech, maxTotalChars);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : budgeted.split("\n")) {
            for (String sentence : SENTENCE_END.split(paragraph)) {
                String piece = sentence.strip();
                if (piece.isEmpty()) {
                    continue;
                }
                while (piece.length() > chunkChars) {
                    flush(chunks, current);
                    String head = cutAtBoundary(piece, chunkChars);
                    chunks.add(head);
                    piece = piece.substring(head.length()).strip();
                }
                if (current.length() + piece.length() + 1 > chunkChars) {
                    flush(chunks, current);
                }
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(piece);
            }
        }
        flush(chunks, current);
        return List.copyOf(chunks);
    }

    /** First {@code maxChars} characters ending at a sentence, line or word boundary, with an ellipsis if cut. */
    public static String cutAtBoundary(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        int limit = Math.max(1, maxChars - 1);
        String head = trimmed.substring(0, limit);
        int cut = -1;
        for (int i = head.length() - 1; i >= limit / 2; i--) {
            char c = head.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '…') {
                cut = i + 1;
                break;
            }
        }
        if (cut < 0) {
            int space = head.lastIndexOf(' ');
            cut = space > limit / 2 ? space : limit;
        }
        String result = head.substring(0, cut).strip();
        return result.endsWith(".") || result.endsWith("!") || result.endsWith("?") || result.endsWith("…")
                ? result : result + "…";
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString().strip());
            current.setLength(0);
        }
    }

    private static String tidy(String text) {
        String out = BLANK_RUN.matcher(text).replaceAll(" ");
        out = out.replaceAll(" *\\n *", "\n");
        out = LINE_RUN.matcher(out).replaceAll("\n\n");
        return out.strip();
    }
}
