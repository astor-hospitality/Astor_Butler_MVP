package museon_online.astor_butler.api.glasses;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The page people open after a glasses walk-through. Read-only, server-rendered HTML without scripts.
 * Protected by a server-only password, not by the mobile bearer; the mobile client never sees this.
 */
@RestController
public class GlassesReportController {
    private static final int RATE_LIMIT = 30;
    private final GlassesReportAuth auth;
    private final GlassesSessionJournal journal;
    private final GlassesS3Storage storage;

    public GlassesReportController(GlassesReportAuth auth, GlassesSessionJournal journal, GlassesS3Storage storage) {
        this.auth = auth;
        this.journal = journal;
        this.storage = storage;
    }

    @GetMapping("/api/glasses/sessions")
    public ResponseEntity<?> sessions(HttpServletRequest request) {
        try {
            var scope = auth.authorize(request);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(journal.sessions(scope));
        } catch (GlassesFailure failure) {
            return error(failure);
        }
    }

    @GetMapping("/api/glasses/sessions/{sessionId}/report")
    public ResponseEntity<?> report(HttpServletRequest request, @PathVariable String sessionId) {
        try {
            var scope = auth.authorize(request);
            var session = journal.session(scope, uuid(sessionId));
            if (session == null) throw new GlassesFailure(404, "SESSION_NOT_FOUND", "No journal for this session");
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'")
                    .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                    .body(render(session));
        } catch (GlassesFailure failure) {
            return error(failure);
        }
    }

    @GetMapping("/api/glasses/sessions/{sessionId}/photo/{requestId}")
    public ResponseEntity<?> photo(HttpServletRequest request, @PathVariable String sessionId, @PathVariable String requestId) {
        try {
            var scope = auth.authorize(request);
            var session = journal.session(scope, uuid(sessionId));
            String id = uuid(requestId);
            boolean known = session != null && session.entries().stream()
                    .anyMatch(e -> "ASSIST".equals(e.type()) && "image".equals(e.kind()) && id.equals(e.requestId()));
            byte[] jpeg = known ? storage.material(scope, id, "input.jpg") : null;
            if (jpeg == null) throw new GlassesFailure(404, "PHOTO_NOT_AVAILABLE", "Photo is not readable from the archive");
            return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.IMAGE_JPEG).body(jpeg);
        } catch (GlassesFailure failure) {
            return error(failure);
        }
    }

    private String uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid identifier");
        }
    }

    private ResponseEntity<?> error(GlassesFailure failure) {
        var response = ResponseEntity.status(failure.status).header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON);
        if (failure.status == 401) response.header("WWW-Authenticate", GlassesReportAuth.challenge());
        if (failure.status == 429) response.header("Retry-After", "60");
        return response.body(new GlassesController.ErrorResponse(null, Map.of("code", failure.code, "message", failure.getMessage())));
    }

    // ---- rendering -------------------------------------------------------------------------------------

    static String render(GlassesSessionJournal.Session session) {
        var entries = new ArrayList<>(session.entries());
        entries.sort(Comparator.comparing(GlassesSessionJournal.Entry::at));
        var summary = GlassesSessionJournal.summary(session);
        var calls = calls(entries);
        var html = new StringBuilder();
        html.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Astor Glass · смена</title><style>")
                .append("body{font-family:-apple-system,system-ui,sans-serif;margin:0;padding:24px 16px;color:#1d1a16;background:#f7f4ee;max-width:960px;margin:0 auto}")
                .append("h1{font-weight:500;font-size:22px;margin:0 0 4px}h2{font-weight:500;font-size:17px;margin:28px 0 8px}")
                .append(".meta{color:#6b655c;font-size:14px}.cards{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}")
                .append(".card{background:#fff;border:1px solid #e6e0d6;border-radius:10px;padding:10px 14px;min-width:120px}")
                .append(".card b{display:block;font-size:20px;font-weight:500}table{border-collapse:collapse;width:100%;background:#fff;border:1px solid #e6e0d6;border-radius:10px}")
                .append("th,td{text-align:left;vertical-align:top;padding:8px 10px;border-top:1px solid #efeae1;font-size:14px}th{color:#6b655c;font-weight:500;border-top:0}")
                .append("td.t{white-space:nowrap;color:#6b655c}.tag{display:inline-block;padding:1px 7px;border-radius:999px;font-size:12px;background:#ece6da}")
                .append(".err{background:#f6d9d2;color:#7a2416}.call{background:#dce8f3}.ok{background:#dcefdf}img{max-width:320px;max-height:240px;border-radius:8px;display:block;margin-top:6px}")
                .append(".ans{white-space:pre-wrap}.note{color:#6b655c}</style></head><body>");
        html.append("<h1>Смена · ").append(esc(scenarioTitle(session.scenarioCode()))).append("</h1>")
                .append("<div class=\"meta\">Сессия ").append(esc(session.sessionId())).append(" · начало ").append(esc(session.startedAt()))
                .append(" · последнее событие ").append(esc(session.updatedAt())).append(" · длительность ")
                .append(esc(duration(session.startedAt(), session.updatedAt()))).append("</div>");
        html.append("<div class=\"cards\">")
                .append(card("Запросов к Астору", summary.requests()))
                .append(card("Ошибок", summary.errors()))
                .append(card("Звонков", calls.size()))
                .append(card("Событий с телефона", summary.clientEvents()))
                .append("</div>");

        html.append("<h2>Шаги</h2>");
        var steps = steps(entries, session.scenarioCode());
        if (steps.isEmpty()) {
            html.append("<p class=\"note\">Телефон не прислал начала шагов; ниже только запросы к Астору.</p>");
        } else {
            html.append("<table><tr><th>Шаг</th><th>Начало</th><th>Длительность</th><th>Запросов</th><th>Фото</th><th>Ошибок</th></tr>");
            for (Step step : steps) {
                html.append("<tr><td>").append(esc(step.stage)).append("</td><td class=\"t\">").append(esc(time(step.startedAt)))
                        .append("</td><td>").append(esc(step.endedAt == null ? "не завершён" : duration(step.startedAt, step.endedAt)))
                        .append("</td><td>").append(step.requests).append("</td><td>").append(step.photos)
                        .append("</td><td>").append(step.errors).append("</td></tr>");
            }
            html.append("</table>");
        }

        if (!calls.isEmpty()) {
            html.append("<h2>Звонки</h2><table><tr><th>Начало</th><th>Длительность</th><th>Во время шага</th></tr>");
            for (Call call : calls) {
                html.append("<tr><td class=\"t\">").append(esc(time(call.startedAt))).append("</td><td>")
                        .append(esc(call.endedAt == null ? "не завершён" : duration(call.startedAt, call.endedAt)))
                        .append("</td><td>").append(esc(call.stage == null ? "—" : call.stage)).append("</td></tr>");
            }
            html.append("</table>");
        }

        html.append("<h2>Таймлайн</h2><table><tr><th>Время</th><th>Что</th><th>Шаг</th><th>Подробности</th></tr>");
        for (var entry : entries) {
            html.append("<tr><td class=\"t\">").append(esc(time(entry.at()))).append("</td><td>");
            if ("ASSIST".equals(entry.type())) {
                html.append("<span class=\"tag ").append(entry.error() == null ? "ok" : "err").append("\">")
                        .append(esc(kindTitle(entry.kind()))).append("</span>");
            } else {
                html.append("<span class=\"tag").append(entry.type().startsWith("CALL") ? " call" : "")
                        .append("CLIENT_ERROR".equals(entry.type()) ? " err" : "").append("\">")
                        .append(esc(eventTitle(entry.type()))).append("</span>");
            }
            html.append("</td><td>").append(esc(entry.stageCode() == null ? "" : entry.stageCode())).append("</td><td>");
            if ("ASSIST".equals(entry.type())) {
                if (entry.error() != null) {
                    html.append("<b>").append(esc(entry.error())).append("</b>");
                } else {
                    html.append("<div class=\"ans\">").append(esc(entry.text() == null ? "" : entry.text())).append("</div>");
                }
                html.append("<div class=\"note\">").append(entry.latencyMs() == null ? "" : entry.latencyMs() + " мс")
                        .append(Boolean.TRUE.equals(entry.archived()) ? " · в архиве" : entry.error() == null ? " · без архива" : "")
                        .append(" · ").append(esc(entry.requestId() == null ? "" : entry.requestId())).append("</div>");
                if ("image".equals(entry.kind()) && entry.error() == null && entry.requestId() != null) {
                    html.append("<img src=\"photo/").append(esc(entry.requestId())).append("\" alt=\"фото шага; если не открылось — доступ к архиву не выдан\">");
                }
            } else if (entry.note() != null) {
                html.append("<span class=\"note\">").append(esc(entry.note())).append("</span>");
            }
            html.append("</td></tr>");
        }
        html.append("</table><p class=\"note\">Информационный помощник. Ответы Астора не подтверждают поручения, брони или заказы.</p></body></html>");
        return html.toString();
    }

    private record Step(String stage, String startedAt, String endedAt, int requests, int photos, int errors) { }
    private record Call(String startedAt, String endedAt, String stage) { }

    private static List<Step> steps(List<GlassesSessionJournal.Entry> entries, String scenarioCode) {
        var result = new ArrayList<Step>();
        String stage = null, startedAt = null;
        int requests = 0, photos = 0, errors = 0;
        for (var entry : entries) {
            if ("STEP_STARTED".equals(entry.type()) || "SESSION_FINISHED".equals(entry.type())) {
                if (stage != null) result.add(new Step(stage, startedAt, entry.at(), requests, photos, errors));
                stage = "STEP_STARTED".equals(entry.type()) ? entry.stageCode() : null;
                startedAt = entry.at();
                requests = photos = errors = 0;
            } else if ("ASSIST".equals(entry.type()) && stage != null && stage.equals(entry.stageCode())) {
                requests++;
                if ("image".equals(entry.kind())) photos++;
                if (entry.error() != null) errors++;
            }
        }
        if (stage != null) result.add(new Step(stage, startedAt, null, requests, photos, errors));
        return result;
    }

    private static List<Call> calls(List<GlassesSessionJournal.Entry> entries) {
        var result = new ArrayList<Call>();
        String stage = null;
        Call open = null;
        for (var entry : entries) {
            if ("STEP_STARTED".equals(entry.type())) stage = entry.stageCode();
            if ("CALL_STARTED".equals(entry.type())) {
                if (open != null) result.add(open);
                open = new Call(entry.at(), null, stage);
            } else if ("CALL_ENDED".equals(entry.type()) && open != null) {
                result.add(new Call(open.startedAt, entry.at(), open.stage));
                open = null;
            }
        }
        if (open != null) result.add(open);
        return result;
    }

    private static String card(String title, int value) {
        return "<div class=\"card\"><b>" + value + "</b>" + esc(title) + "</div>";
    }

    private static String scenarioTitle(String code) {
        return switch (code == null ? "" : code) {
            case "SHIFT" -> "смены";
            case "BUSINESS_LUNCH_TWO" -> "бизнес-ланча на двоих";
            default -> code;
        };
    }

    private static String kindTitle(String kind) {
        return switch (kind == null ? "" : kind) {
            case "image" -> "фото";
            case "audio" -> "голос";
            case "text" -> "текст";
            default -> kind;
        };
    }

    private static String eventTitle(String type) {
        return switch (type) {
            case "STEP_STARTED" -> "шаг начат";
            case "STEP_DONE" -> "шаг завершён";
            case "CALL_STARTED" -> "звонок начался";
            case "CALL_ENDED" -> "звонок окончен";
            case "SESSION_FINISHED" -> "показ завершён";
            case "CLIENT_ERROR" -> "ошибка на телефоне";
            default -> type;
        };
    }

    private static String time(String iso) {
        try {
            return iso.substring(11, 19) + " UTC";
        } catch (RuntimeException e) {
            return iso == null ? "" : iso;
        }
    }

    private static String duration(String from, String to) {
        try {
            Duration d = Duration.between(Instant.parse(from), Instant.parse(to));
            if (d.isNegative()) return "—";
            long s = d.getSeconds();
            return s < 60 ? s + " с" : s / 60 + " мин " + s % 60 + " с";
        } catch (RuntimeException e) {
            return "—";
        }
    }

    static String esc(String text) {
        if (text == null) return "";
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
