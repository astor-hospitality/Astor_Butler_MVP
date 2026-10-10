package museon_online.astor_butler.max.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import museon_online.astor_butler.service.message.IncomingMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the MAX update JSON (platform-api.max.ru, {@code GET /updates}) into {@link MaxInbound}, and a bound
 * {@link MaxInbound} into the channel-neutral {@link IncomingMessage}. Pure functions, no I/O.
 *
 * <ul>
 *   <li>{@code message_created}: the guest wrote something. Text as is; a {@code contact} attachment becomes the
 *       contact phone (the consent step); an {@code audio} attachment marks the message as a voice note.</li>
 *   <li>{@code bot_started}: the guest pressed "Start" or opened a deep link. Becomes {@code /start} or
 *       {@code /start <payload>}, exactly the text Telegram delivers for {@code t.me/bot?start=<payload>}, so the
 *       first-touch and the business-lunch handoff ({@code lunch_...}) work unchanged.</li>
 *   <li>{@code message_callback}: an inline callback button. Its payload is read as the guest's words.</li>
 * </ul>
 * Everything else (bot added/removed, edits, chat title changes) is ignored in phase 1.
 */
public class MaxUpdateMapper {

    public static final String MESSAGE_CREATED = "message_created";
    public static final String BOT_STARTED = "bot_started";
    public static final String MESSAGE_CALLBACK = "message_callback";

    private static final Pattern VCARD_TEL = Pattern.compile("(?im)^TEL[^:\\r\\n]*:([^\\r\\n]+)$");

    public Optional<MaxInbound> read(JsonNode update) {
        if (update == null || update.isNull()) {
            return Optional.empty();
        }
        String type = update.path("update_type").asText("");
        return switch (type) {
            case MESSAGE_CREATED -> messageCreated(update);
            case BOT_STARTED -> botStarted(update);
            case MESSAGE_CALLBACK -> callback(update);
            default -> Optional.empty();
        };
    }

    public IncomingMessage toIncoming(MaxInbound inbound, long internalChatId) {
        return IncomingMessage.max(
                internalChatId,
                inbound.userId(),
                inbound.text() == null ? "" : inbound.text(),
                inbound.contactPhone(),
                inbound.firstName(),
                inbound.lastName(),
                inbound.username(),
                language(inbound.locale()),
                inbound.fromBot(),
                "max:" + inbound.eventKey(),
                inbound.payload()
        );
    }

    private Optional<MaxInbound> messageCreated(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode sender = message.path("sender");
        JsonNode recipient = message.path("recipient");
        JsonNode body = message.path("body");
        Long chatId = longOrNull(recipient.path("chat_id"));
        String mid = text(body.path("mid"));
        if (chatId == null || mid == null) {
            return Optional.empty();
        }
        Map<String, Object> payload = basePayload(MESSAGE_CREATED, chatId, sender, recipient);
        payload.put("maxMessageId", mid);
        Long senderId = longOrNull(sender.path("user_id"));
        String bodyText = body.path("text").asText("");
        String contactPhone = null;
        for (JsonNode attachment : body.path("attachments")) {
            String kind = attachment.path("type").asText("");
            if ("contact".equals(kind) && contactPhone == null) {
                JsonNode contact = attachment.path("payload");
                if (ownContact(contact, senderId)) {
                    contactPhone = contactPhone(contact);
                } else {
                    // A contact attached by hand or forwarded is somebody's card, not the consent button.
                    payload.put("maxContactIgnored", "NOT_OWN_CONTACT");
                }
            } else if ("audio".equals(kind) && !payload.containsKey("mediaKind")) {
                payload.put("mediaKind", "VOICE");
                String url = text(attachment.path("payload").path("url"));
                if (url != null) {
                    payload.put("maxAudioUrl", url);
                }
                String transcript = text(attachment.path("transcription"));
                if (transcript != null && bodyText.isBlank()) {
                    // MAX transcribes voice notes itself; the FSM reads the words, as after Telegram's STT.
                    bodyText = transcript.strip();
                    payload.put("transcriptionAvailable", true);
                    payload.put("transcriptionStatus", "TRANSCRIBED");
                    payload.put("transcriptionProvider", "max");
                    payload.put("transcript", bodyText);
                } else if (transcript == null) {
                    // Server-side STT for MAX audio is phase 3; the gateway then asks for text.
                    payload.put("transcriptionAvailable", false);
                    payload.put("transcriptionStatus", "PENDING");
                    payload.put("transcriptionReason", "STT disabled");
                }
            } else if (!kind.isBlank()) {
                payload.putIfAbsent("maxAttachmentType", kind);
            }
        }
        return Optional.of(new MaxInbound(
                MESSAGE_CREATED,
                mid,
                chatId,
                longOrNull(sender.path("user_id")),
                recipient.path("chat_type").asText(""),
                bodyText,
                contactPhone,
                firstName(sender),
                text(sender.path("last_name")),
                text(sender.path("username")),
                locale(update),
                sender.path("is_bot").asBoolean(false),
                null,
                payload
        ));
    }

    private Optional<MaxInbound> botStarted(JsonNode update) {
        Long chatId = longOrNull(update.path("chat_id"));
        JsonNode user = update.path("user");
        Long userId = longOrNull(user.path("user_id"));
        if (chatId == null || userId == null) {
            return Optional.empty();
        }
        String startPayload = text(update.path("payload"));
        Map<String, Object> payload = basePayload(BOT_STARTED, chatId, user, null);
        payload.put("maxChatType", "dialog");
        if (startPayload != null) {
            payload.put("startPayload", startPayload);
        }
        String key = "started:" + chatId + ":" + update.path("timestamp").asText("");
        return Optional.of(new MaxInbound(
                BOT_STARTED,
                key,
                chatId,
                userId,
                "dialog",
                startPayload == null ? "/start" : "/start " + startPayload,
                null,
                firstName(user),
                text(user.path("last_name")),
                text(user.path("username")),
                locale(update),
                user.path("is_bot").asBoolean(false),
                null,
                payload
        ));
    }

    private Optional<MaxInbound> callback(JsonNode update) {
        JsonNode callback = update.path("callback");
        String callbackId = text(callback.path("callback_id"));
        JsonNode message = update.path("message");
        JsonNode recipient = message.path("recipient");
        Long chatId = longOrNull(recipient.path("chat_id"));
        JsonNode user = callback.path("user");
        if (callbackId == null || chatId == null) {
            return Optional.empty();
        }
        Map<String, Object> payload = basePayload(MESSAGE_CALLBACK, chatId, user, recipient);
        payload.put("maxCallbackId", callbackId);
        String data = callback.path("payload").asText("");
        payload.put("callbackPayload", data);
        return Optional.of(new MaxInbound(
                MESSAGE_CALLBACK,
                "callback:" + callbackId,
                chatId,
                longOrNull(user.path("user_id")),
                recipient.path("chat_type").asText(""),
                MaxCallbackPayload.guestText(data),
                null,
                firstName(user),
                text(user.path("last_name")),
                text(user.path("username")),
                locale(update),
                user.path("is_bot").asBoolean(false),
                callbackId,
                payload
        ));
    }

    private Map<String, Object> basePayload(String type, long chatId, JsonNode user, JsonNode recipient) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("maxUpdateType", type);
        payload.put("maxChatId", chatId);
        Long userId = longOrNull(user.path("user_id"));
        if (userId != null) {
            payload.put("maxUserId", userId);
        }
        if (recipient != null && !recipient.path("chat_type").asText("").isBlank()) {
            payload.put("maxChatType", recipient.path("chat_type").asText());
        }
        return payload;
    }

    /**
     * Whether the shared contact is the sender's own. MAX adds {@code hash} only when the contact came from a
     * {@code request_contact} button (the consent step); {@code max_info} is the MAX user behind the card. Either
     * proves the guest shared their own number; a contact attached by hand or forwarded has neither for the sender.
     * Verifying {@code hash} as HMAC-SHA256(token, vcf_info) is a phase 2 hardening (its encoding is undocumented).
     */
    static boolean ownContact(JsonNode contactPayload, Long senderId) {
        if (text(contactPayload.path("hash")) != null) {
            return true;
        }
        Long owner = longOrNull(contactPayload.path("max_info").path("user_id"));
        return owner != null && owner.equals(senderId);
    }

    /** The phone from the vCard MAX attaches to a shared contact ({@code payload.vcf_info}). */
    static String contactPhone(JsonNode contactPayload) {
        String vcf = text(contactPayload.path("vcf_info"));
        if (vcf != null) {
            // Some payloads carry the vCard line breaks as literal "\r\n" text.
            vcf = vcf.replace("\\r\\n", "\n").replace("\\n", "\n");
            Matcher matcher = VCARD_TEL.matcher(vcf);
            if (matcher.find()) {
                String phone = matcher.group(1).trim();
                if (!phone.isEmpty()) {
                    return phone;
                }
            }
        }
        return text(contactPayload.path("phone"));
    }

    private static String firstName(JsonNode user) {
        String first = text(user.path("first_name"));
        return first != null ? first : text(user.path("name"));
    }

    private static String locale(JsonNode update) {
        return text(update.path("user_locale"));
    }

    /** {@code ru-RU} / {@code ru_RU} / {@code ru} → {@code ru}, the form Telegram's language_code has. */
    static String language(String locale) {
        if (locale == null || locale.isBlank()) {
            return null;
        }
        String value = locale.trim();
        int cut = value.indexOf('-') >= 0 ? value.indexOf('-') : value.indexOf('_');
        return (cut > 0 ? value.substring(0, cut) : value).toLowerCase(java.util.Locale.ROOT);
    }

    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String value = node.asText("").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }
}
