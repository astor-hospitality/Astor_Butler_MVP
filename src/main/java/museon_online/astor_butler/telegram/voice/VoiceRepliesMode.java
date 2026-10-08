package museon_online.astor_butler.telegram.voice;

import java.util.Locale;

/**
 * {@code ASTOR_TELEGRAM_VOICE_REPLIES}: {@code off} (default, nothing changes), {@code on} (every reply in a
 * private chat gets a voice note) or {@code auto} (voice only when the guest spoke or switched the chat to
 * hands-free with {@code /voice on}).
 */
public enum VoiceRepliesMode {
    OFF,
    ON,
    AUTO;

    public static VoiceRepliesMode parse(String value) {
        String name = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "on", "true", "always" -> ON;
            case "auto" -> AUTO;
            case "", "off", "false", "none" -> OFF;
            default -> throw new IllegalStateException(
                    "ASTOR_TELEGRAM_VOICE_REPLIES must be off, on or auto, got '" + name + "'");
        };
    }
}
