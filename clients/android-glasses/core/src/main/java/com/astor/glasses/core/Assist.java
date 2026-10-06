package com.astor.glasses.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;

/**
 * The informational assist contract of the Astor backend: POST {base}/api/glasses/assist.
 * Building a request and judging a reply are kept apart from the network so both can be tested.
 */
public final class Assist {
    private Assist() { }

    public static final int MAX_TEXT = 4000, MAX_MEDIA_BYTES = 2 * 1024 * 1024, MAX_REPLY_BYTES = 3 * 1024 * 1024,
            MAX_ANSWER_CHARS = 12000;

    public static final class Refused extends RuntimeException {
        Refused(String message) { super(message); }
    }

    /**
     * The assist address for a backend base address. Only https with a host and nothing extra: no user,
     * query or fragment. Plain http is accepted for the computer's own loopback when a debug build asks
     * for it, so the client can be tried against a local stand-in server on an emulator.
     */
    public static URI endpoint(String base, String path, boolean allowLocalHttp) {
        URI uri;
        try {
            uri = new URI(base == null ? "" : base.trim());
        } catch (Exception e) {
            throw new Refused("Адрес сервера записан неверно.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost();
        boolean local = "10.0.2.2".equals(host) || "127.0.0.1".equals(host) || "localhost".equals(host);
        boolean schemeOk = scheme.equals("https") || (allowLocalHttp && scheme.equals("http") && local);
        if (!schemeOk || host == null || host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new Refused("Нужен HTTPS-адрес сервера без логина, параметров и якоря.");
        }
        String prefix = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return URI.create(scheme + "://" + uri.getRawAuthority() + prefix + path);
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** The JSON body of one question. Exactly one of image and audio may be present; both may be absent. */
    public static String body(String requestId, String text, byte[] jpeg, byte[] aac, JSONObject photoContext) {
        if (Uuids.parse(requestId) == null) throw new Refused("Нет идентификатора запроса.");
        String question = text == null ? "" : text;
        if (question.length() > MAX_TEXT || tooLarge(jpeg) || tooLarge(aac)) throw new Refused("Запрос превышает допустимый размер.");
        if (jpeg != null && aac != null) throw new Refused("В одном запросе либо фото, либо голос.");
        if (jpeg == null && aac == null && question.trim().isEmpty()) throw new Refused("Введите вопрос или запишите голос.");
        try {
            JSONObject body = new JSONObject().put("requestId", requestId).put("text", question);
            if (jpeg != null) body.put("imageBase64", Base64.getEncoder().encodeToString(jpeg)).put("imageMimeType", "image/jpeg");
            if (aac != null) body.put("audioBase64", Base64.getEncoder().encodeToString(aac)).put("audioMimeType", "audio/mp4");
            if (photoContext != null) body.put("photoContext", photoContext);
            return body.toString();
        } catch (JSONException e) {
            throw new Refused("Запрос не удалось собрать.");
        }
    }

    private static boolean tooLarge(byte[] media) {
        return media != null && (media.length == 0 || media.length > MAX_MEDIA_BYTES);
    }

    /**
     * The answer text when the reply belongs to this request and carries a non-empty answer; null otherwise.
     * A reply to another request must never replace the current answer.
     */
    public static String answer(JSONObject reply, String requestId) {
        if (reply == null) return null;
        UUID expected = Uuids.parse(requestId), received = Uuids.parse(reply.opt("requestId"));
        Object text = reply.opt("text");
        if (expected == null || !expected.equals(received) || !(text instanceof String)) return null;
        String answer = (String) text;
        return answer.length() <= MAX_ANSWER_CHARS && !answer.trim().isEmpty() ? answer : null;
    }

    /**
     * Speech prepared by the server, when it is present, of a known type and within the size limit.
     * As on the iPhone, only the agreed male voice is played; otherwise the phone reads the text itself.
     */
    public static byte[] speech(JSONObject reply) {
        Object encoded = reply.opt("audioBase64"), mime = reply.opt("audioMimeType");
        if (!(encoded instanceof String) || !"male".equals(reply.opt("audioVoiceGender"))
                || !("audio/mpeg".equals(mime) || "audio/mp4".equals(mime))) return null;
        String text = (String) encoded;
        if (text.isEmpty() || text.length() > ((MAX_MEDIA_BYTES + 2) / 3) * 4) return null;
        try {
            byte[] audio = Base64.getDecoder().decode(text);
            return audio.length > 0 && audio.length <= MAX_MEDIA_BYTES ? audio : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
