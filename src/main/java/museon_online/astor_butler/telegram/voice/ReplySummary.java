package museon_online.astor_butler.telegram.voice;

import java.util.List;

/**
 * The compact text next to the voice note.
 *
 * @param text     2–4 plain lines, at most the configured cap, links included as lines when there are any
 * @param links    the answer's links in order, for the buttons under the voice note
 * @param photos   image links among them, sent as photos (first few)
 * @param complete true when the summary already is the whole answer, so "Подробнее" adds nothing
 * @param source   {@code model} or {@code fallback}: how the text was produced, for logs and tests
 */
public record ReplySummary(String text, List<ReplyText.Link> links, List<ReplyText.Link> photos, boolean complete,
                           String source) {
}
