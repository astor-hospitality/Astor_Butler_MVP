package museon_online.astor_butler.max.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.model.GigaChatTrust;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MAX Bot API over plain JDK {@link HttpClient} (the style of the SpeechKit and embeddings clients), no Spring.
 *
 * <ul>
 *   <li>Auth: the bot token in the {@code Authorization} header on every call; never in the URL, never logged.</li>
 *   <li>{@code GET /me} — the bot itself (startup check).</li>
 *   <li>{@code GET /updates?limit&timeout&marker&types} — long polling; answers {@code {updates, marker}}.</li>
 *   <li>{@code POST /messages?chat_id} — send text with an optional inline keyboard attachment.</li>
 *   <li>{@code POST /answers?callback_id} — acknowledge a callback button.</li>
 * </ul>
 *
 * <p>No retries here: the polling loop retries polls, and a send is not repeated automatically because a repeat
 * could show the guest the same answer twice. Errors are {@link MaxApiException} with the HTTP status and the
 * API's {@code code}/{@code message}, never the token.
 */
@Slf4j
public class MaxBotApiClient {

    /** Update types the guest channel handles; the rest are not even requested. */
    public static final List<String> GUEST_UPDATE_TYPES = List.of("message_created", "message_callback", "bot_started");
    private static final int ERROR_SNIPPET = 300;
    private static final Duration POLL_GRACE = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI baseUrl;
    private final String token;
    private final Duration requestTimeout;

    public MaxBotApiClient(HttpClient http, ObjectMapper json, URI baseUrl, String token, Duration requestTimeout) {
        this.http = http;
        this.json = json;
        this.baseUrl = baseUrl;
        this.token = token == null ? "" : token.trim();
        this.requestTimeout = requestTimeout;
    }

    /**
     * The JDK client, trusting the JVM store plus the PEM at {@code caCertPath}: platform-api2.max.ru presents a
     * certificate under the Russian Trusted Root CA (Минцифры), which the JDK does not ship. Verification is never
     * switched off. A blank or unreadable path leaves the JVM trust only and says so in the log, so a wrong path
     * breaks the MAX channel, not the application.
     */
    public static HttpClient httpClient(String caCertPath) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (caCertPath == null || caCertPath.isBlank()) {
            log.warn("MAX_CA_CERT_PATH is not set: platform-api2.max.ru is trusted only if the Russian Trusted Root CA "
                    + "is already in the JVM trust store");
            return builder.build();
        }
        Path path = Path.of(caCertPath.trim());
        try {
            builder.sslContext(GigaChatTrust.sslContext(path));
        } catch (Exception e) {
            log.error("MAX_CA_CERT_PATH could not be loaded ({}): {}; MAX calls will use the JVM trust store only",
                    path, e.toString());
        }
        return builder.build();
    }

    public JsonNode getMe() {
        return call("GET", "/me", Map.of(), null, requestTimeout);
    }

    /**
     * Long poll. {@code timeout} is how long MAX may hold the request open; the HTTP timeout is that plus a grace
     * period so an empty poll ends with an empty answer rather than a client-side timeout.
     */
    public MaxUpdatesPage getUpdates(Long marker, Duration timeout, int limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("limit", Integer.toString(limit));
        query.put("timeout", Long.toString(timeout.toSeconds()));
        if (marker != null) {
            query.put("marker", Long.toString(marker));
        }
        query.put("types", String.join(",", GUEST_UPDATE_TYPES));
        JsonNode body = call("GET", "/updates", query, null, timeout.plus(POLL_GRACE));
        List<JsonNode> updates = new ArrayList<>();
        body.path("updates").forEach(updates::add);
        JsonNode next = body.path("marker");
        return new MaxUpdatesPage(updates, next.isNumber() ? Long.valueOf(next.asLong()) : null);
    }

    /** Sends one message to a chat (dialog or group) by its MAX {@code chat_id}. */
    public JsonNode sendMessage(long chatId, MaxOutgoingMessage message) {
        return call("POST", "/messages", Map.of("chat_id", Long.toString(chatId)), body(message), requestTimeout);
    }

    /** Acknowledges a callback; {@code notification} (may be null) is shown to the guest as a short toast. */
    public void answerCallback(String callbackId, String notification) {
        ObjectNode body = json.createObjectNode();
        if (notification != null && !notification.isBlank()) {
            body.put("notification", notification);
        }
        call("POST", "/answers", Map.of("callback_id", callbackId), body, requestTimeout);
    }

    ObjectNode body(MaxOutgoingMessage message) {
        ObjectNode body = json.createObjectNode();
        body.put("text", message.text());
        if (message.format() != null) {
            body.put("format", message.format());
        }
        body.put("notify", message.notifyGuest());
        ArrayNode attachments = body.putArray("attachments");
        if (message.hasKeyboard()) {
            ObjectNode keyboard = attachments.addObject();
            keyboard.put("type", "inline_keyboard");
            ArrayNode rows = keyboard.putObject("payload").putArray("buttons");
            for (List<MaxButton> row : message.keyboard()) {
                ArrayNode buttons = rows.addArray();
                for (MaxButton button : row) {
                    ObjectNode node = buttons.addObject();
                    node.put("type", button.kind().apiType());
                    node.put("text", button.text());
                    if (button.payload() != null) {
                        node.put("payload", button.payload());
                    }
                    if (button.url() != null) {
                        node.put("url", button.url());
                    }
                }
            }
        }
        return body;
    }

    private JsonNode call(String method, String path, Map<String, String> query, JsonNode body, Duration timeout) {
        if (token.isEmpty()) {
            throw new MaxApiException(0, "", "MAX_BOT_TOKEN is not set; the MAX Bot API cannot authenticate");
        }
        URI uri = uri(path, query);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", token)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new MaxApiException(0, "MAX " + method + " " + path + " timed out after " + timeout.toMillis() + " ms", e);
        } catch (IOException e) {
            throw new MaxApiException(0, "MAX " + method + " " + path + " failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MaxApiException(0, "MAX " + method + " " + path + " interrupted", e);
        }
        int status = response.statusCode();
        JsonNode parsed = parse(response.body());
        if (status < 200 || status >= 300) {
            String code = parsed == null ? "" : parsed.path("code").asText("");
            String message = parsed == null ? snippet(response.body()) : parsed.path("message").asText(snippet(response.body()));
            throw new MaxApiException(status, code, "MAX " + method + " " + path + " answered HTTP " + status
                    + (code.isEmpty() ? "" : " " + code) + (message.isEmpty() ? "" : ": " + message));
        }
        return parsed == null ? json.createObjectNode() : parsed;
    }

    private URI uri(String path, Map<String, String> query) {
        String base = baseUrl.toString();
        String encoded = query.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(base + path + (encoded.isEmpty() ? "" : "?" + encoded));
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, 0, Math.min(body.length, ERROR_SNIPPET), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
    }
}
