package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.domain.glasses.GlassesTranscriptFeed;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The shift's own view of what went through the glasses, for the staff portal: the last exchanges with
 * the question, the answer and whether a photo came with it.
 *
 * It lives under /api/staff/**, so the portal's own security chain guards it: a staff JWT, and nothing at
 * all until `astor.staff.enabled` is on. Until then the system chat is where these exchanges are read —
 * this page is deliberately not given a second, weaker door.
 *
 * Frames are not served here: a photo lives in the private archive and in the system chat, and this page
 * only says that one existed, so a page left open in a hall shows no guest's face.
 */
@RestController
public class GlassesFeedController {
    private final GlassesTranscriptFeed feed;

    public GlassesFeedController(GlassesTranscriptFeed feed) {
        this.feed = feed;
    }

    @GetMapping("/api/staff/glasses-feed")
    public ResponseEntity<?> json() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entries", feed.recent(GlassesTranscriptFeed.LIMIT)));
    }

    @GetMapping("/api/staff/glasses-feed/page")
    public ResponseEntity<?> page() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(render(feed.recent(GlassesTranscriptFeed.LIMIT)));
    }

    static String render(List<GlassesTranscriptFeed.Entry> entries) {
        var html = new StringBuilder();
        html.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Astor Glass · смена</title><style>")
                .append("body{font-family:-apple-system,system-ui,sans-serif;margin:0 auto;padding:24px 16px;max-width:760px;color:#1d1a16;background:#f7f4ee}")
                .append("h1{font-weight:500;font-size:21px;margin:0 0 16px}.row{background:#fff;border:1px solid #e6e0d6;border-radius:12px;padding:12px 14px;margin-bottom:10px}")
                .append(".t{color:#6b655c;font-size:13px;margin-bottom:6px}.q{font-weight:500;white-space:pre-wrap}.a{white-space:pre-wrap;margin-top:6px}")
                .append(".tag{display:inline-block;padding:1px 7px;border-radius:999px;font-size:12px;background:#ece6da;margin-left:6px}")
                .append(".note{color:#6b655c;font-size:13px}</style></head><body>");
        html.append("<h1>Что проходило через очки</h1>");
        if (entries.isEmpty()) {
            html.append("<p class=\"note\">Пока ничего: либо смена не начиналась, либо передача в Butler выключена.</p>");
        }
        for (var entry : entries) {
            html.append("<div class=\"row\"><div class=\"t\">").append(esc(time(entry.at())))
                    .append(" · ").append(esc(kind(entry.kind())));
            if (entry.stageCode() != null && !entry.stageCode().isBlank()) {
                html.append("<span class=\"tag\">").append(esc(entry.stageCode())).append("</span>");
            }
            if (entry.photo()) html.append("<span class=\"tag\">фото в чате</span>");
            html.append("</div>");
            if (!entry.question().isBlank()) html.append("<div class=\"q\">").append(esc(entry.question())).append("</div>");
            html.append("<div class=\"a\">").append(esc(entry.answer())).append("</div></div>");
        }
        html.append("<p class=\"note\">Последние сутки, до 200 записей, в памяти сервиса. Полная запись — в системном чате; кадры — в приватном архиве. Ответы Астора ничего не подтверждают.</p>");
        return html.append("</body></html>").toString();
    }

    private static String kind(String kind) {
        return switch (kind == null ? "" : kind) {
            case "image" -> "фото";
            case "audio" -> "голос";
            case "text" -> "текст";
            default -> kind;
        };
    }

    private static String time(String iso) {
        try {
            return iso.substring(11, 19) + " UTC";
        } catch (RuntimeException e) {
            return iso == null ? "" : iso;
        }
    }

    private static String esc(String text) {
        return GlassesReportController.esc(text);
    }
}
