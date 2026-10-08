package museon_online.astor_butler.telegram.voice;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the full answer into the short text a guest reads at a glance: the gist, then the links. A model call
 * through the existing {@link ModelGateway} when allowed, and always a deterministic fallback (first sentences
 * plus a bullet of links) when the model is off, blank, over the cap or failing — so the summary never blocks the
 * reply on a model outage.
 */
@Slf4j
@Service
public class ReplySummarizer {

    static final String PURPOSE = "telegram-voice-summary";
    static final String SCENARIO = "TelegramVoiceReply";

    private final ModelGateway modelGateway;
    private final TelegramVoiceReplyConfig config;

    public ReplySummarizer(ModelGateway modelGateway, TelegramVoiceReplyConfig config) {
        this.modelGateway = modelGateway;
        this.config = config;
    }

    public ReplySummary summarize(String fullText) {
        String plain = ReplyText.plain(fullText);
        List<ReplyText.Link> links = ReplyText.links(fullText);
        List<ReplyText.Link> photos = links.stream().filter(ReplyText.Link::image).limit(config.maxPhotos()).toList();
        int max = config.summaryMaxChars();

        if (plain.isBlank()) {
            return new ReplySummary("", links, photos, true, "fallback");
        }
        if (plain.length() <= max && lineCount(plain) <= 4) {
            return new ReplySummary(plain, links, photos, true, "fallback");
        }

        if (config.summaryViaModel()) {
            String fromModel = fromModel(plain, links);
            if (fromModel != null) {
                return new ReplySummary(fromModel, links, photos, false, "model");
            }
        }
        return new ReplySummary(fallback(plain, links, max), links, photos, false, "fallback");
    }

    /** Deterministic: the first sentences that fit, then one line per link; always within the cap. */
    static String fallback(String plain, List<ReplyText.Link> links, int max) {
        List<String> linkLines = new ArrayList<>();
        for (ReplyText.Link link : links.stream().limit(3).toList()) {
            linkLines.add("• " + link.url());
        }
        String linksBlock = String.join("\n", linkLines);
        int room = linksBlock.isEmpty() ? max : max - linksBlock.length() - 1;
        String gist = ReplyText.cutAtBoundary(ReplyText.speech(plain).replace("\n", " "), Math.max(60, room));
        String text = linksBlock.isEmpty() ? gist : gist + "\n" + linksBlock;
        return text.length() <= max ? text : ReplyText.cutAtBoundary(text, max);
    }

    private String fromModel(String plain, List<ReplyText.Link> links) {
        String prompt = """
                Сожми ответ ассистента ресторана в краткое сообщение для Telegram: 2–4 строки, по-русски,
                не больше %d символов. Первая строка — суть ответа. Сохрани названия блюд, мест, документов,
                даты и цены как в оригинале. Ссылки оставь как есть, каждую на отдельной строке. Без приветствий,
                без markdown, без эмодзи, без вступлений вроде «Краткое содержание».

                Ответ:
                \"\"\"
                %s
                \"\"\"
                """.formatted(config.summaryMaxChars(), plain);
        try {
            ModelTextResponse response = modelGateway.generateText(ModelTextRequest.of(prompt, SCENARIO, "", PURPOSE));
            String text = response == null ? null : ReplyText.plain(response.text());
            if (text == null || text.isBlank()) {
                log.debug("Voice summary: model returned blank, using fallback");
                return null;
            }
            return normalize(text, links);
        } catch (RuntimeException e) {
            log.warn("Voice summary: model call failed ({}), using fallback", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The model's lines within the cap, with every link of the answer present (appended when it dropped them). */
    private String normalize(String text, List<ReplyText.Link> links) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        int max = config.summaryMaxChars();
        List<String> missing = new ArrayList<>();
        for (ReplyText.Link link : links.stream().limit(3).toList()) {
            if (!text.contains(link.url())) {
                missing.add("• " + link.url());
            }
        }
        int linksLength = missing.stream().mapToInt(line -> line.length() + 1).sum();
        String body = String.join("\n", lines.subList(0, Math.min(lines.size(), 4)));
        if (body.length() + linksLength > max) {
            body = ReplyText.cutAtBoundary(body, Math.max(60, max - linksLength));
        }
        String result = missing.isEmpty() ? body : body + "\n" + String.join("\n", missing);
        return result.length() <= max ? result : ReplyText.cutAtBoundary(result, max);
    }

    private static int lineCount(String text) {
        return (int) text.lines().filter(line -> !line.isBlank()).count();
    }
}
