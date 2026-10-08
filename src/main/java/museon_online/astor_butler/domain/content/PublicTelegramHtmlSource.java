package museon_online.astor_butler.domain.content;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class PublicTelegramHtmlSource implements VenueContentSource {

    private static final Pattern MESSAGE_START = Pattern.compile(
            "<div class=\"tgme_widget_message[^>]*data-post=\"([^\"]+)\"[^>]*>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MESSAGE_TEXT = Pattern.compile(
            "<div class=\"tgme_widget_message_text[^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern MESSAGE_TIME = Pattern.compile(
            "<time[^>]*datetime=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHOTO_URL = Pattern.compile(
            "background-image:url\\('([^']+)'\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern VIDEO_URL = Pattern.compile(
            "<video[^>]*src=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    // Built on first use: t.me is reachable from the VM only through the same egress
    // proxy the bot uses (telegram.bot.proxy.*), so the client must see those settings.
    private volatile HttpClient httpClient;

    @Value("${telegram.bot.proxy.type:NO_PROXY}")
    private String proxyType = "NO_PROXY";

    @Value("${telegram.bot.proxy.host:}")
    private String proxyHost = "";

    @Value("${telegram.bot.proxy.port:0}")
    private int proxyPort;

    @Value("${astor.content.telegram.public-channel.enabled:false}")
    private boolean enabled;

    @Value("${astor.content.telegram.public-channel.venue-code:AERIS}")
    private String venueCode;

    @Value("${astor.content.telegram.public-channel.username:aeris_gastrobar}")
    private String username;

    @Value("${astor.content.telegram.public-channel.url:https://t.me/s/aeris_gastrobar}")
    private String url;

    @Value("${astor.content.telegram.public-channel.timeout-seconds:8}")
    private long timeoutSeconds;

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<NormalizedVenueContentPost> fetchRecent() {
        if (!enabled) {
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", "AstorButler/1.0 (+local content ingest)")
                    .build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("AERIS channel public ingest failed: status={}", response.statusCode());
                return List.of();
            }
            return parse(response.body());
        } catch (Exception e) {
            log.warn("AERIS channel public ingest failed: {}", e.getMessage());
            return List.of();
        }
    }

    HttpClient client() {
        HttpClient current = httpClient;
        if (current == null) {
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
            proxyAddress().ifPresent(address -> builder.proxy(ProxySelector.of(address)));
            current = builder.build();
            httpClient = current;
        }
        return current;
    }

    /**
     * Only an HTTP proxy can be honoured: java.net.http has no SOCKS support, so a SOCKS
     * setting leaves the ingest on a direct connection (and it logs why it failed).
     */
    java.util.Optional<InetSocketAddress> proxyAddress() {
        String type = proxyType == null ? "" : proxyType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"HTTP".equals(type) || proxyHost == null || proxyHost.isBlank() || proxyPort <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(InetSocketAddress.createUnresolved(proxyHost.trim(), proxyPort));
    }

    List<NormalizedVenueContentPost> parse(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }

        Matcher matcher = MESSAGE_START.matcher(html);
        List<MessageSlice> slices = new ArrayList<>();
        while (matcher.find()) {
            slices.add(new MessageSlice(matcher.group(1), matcher.start()));
        }

        List<NormalizedVenueContentPost> posts = new ArrayList<>();
        for (int i = 0; i < slices.size(); i++) {
            MessageSlice slice = slices.get(i);
            int end = i + 1 < slices.size() ? slices.get(i + 1).start() : html.length();
            String block = html.substring(slice.start(), end);
            NormalizedVenueContentPost post = toPost(slice.dataPost(), block);
            if (post != null) {
                posts.add(post);
            }
        }
        return List.copyOf(posts);
    }

    private NormalizedVenueContentPost toPost(String dataPost, String block) {
        String messageId = sourceMessageId(dataPost);
        if (messageId.isBlank()) {
            return null;
        }
        String text = messageText(block);
        List<VenueContentAsset> assets = assets(block, messageId);
        if (text.isBlank() && assets.isEmpty()) {
            return null;
        }
        String sourceUrl = "https://t.me/%s/%s".formatted(username, messageId);
        String rawPayload = "{\"dataPost\":\"%s\",\"assetCount\":%d}".formatted(escapeJson(dataPost), assets.size());
        return new NormalizedVenueContentPost(
                normalizeVenue(venueCode),
                "TELEGRAM_PUBLIC_HTML",
                username,
                messageId,
                sourceUrl,
                sha256(block),
                publishedAt(block),
                text,
                assets,
                rawPayload
        );
    }

    private String messageText(String block) {
        Matcher matcher = MESSAGE_TEXT.matcher(block);
        if (!matcher.find()) {
            return "";
        }
        return cleanupHtml(matcher.group(1));
    }

    private Instant publishedAt(String block) {
        Matcher matcher = MESSAGE_TIME.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Instant.parse(matcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<VenueContentAsset> assets(String block, String messageId) {
        List<VenueContentAsset> assets = new ArrayList<>();
        Matcher photoMatcher = PHOTO_URL.matcher(block);
        int index = 1;
        while (photoMatcher.find()) {
            assets.add(new VenueContentAsset(
                    "PHOTO",
                    photoMatcher.group(1),
                    null,
                    null,
                    "image/jpeg"
            ));
            index++;
        }
        Matcher videoMatcher = VIDEO_URL.matcher(block);
        while (videoMatcher.find()) {
            assets.add(new VenueContentAsset(
                    "VIDEO",
                    videoMatcher.group(1),
                    null,
                    null,
                    "video/mp4"
            ));
            index++;
        }
        return List.copyOf(assets);
    }

    private String cleanupHtml(String html) {
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String sourceMessageId(String dataPost) {
        if (dataPost == null || dataPost.isBlank()) {
            return "";
        }
        int slash = dataPost.lastIndexOf('/');
        return slash < 0 ? dataPost.trim() : dataPost.substring(slash + 1).trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalizeVenue(String value) {
        return value == null || value.isBlank() ? "AERIS" : value.trim().toUpperCase();
    }

    private record MessageSlice(String dataPost, int start) {
    }
}
