package museon_online.astor_butler.max.client;

import java.util.List;

/**
 * The body of {@code POST /messages}: text, its markup format ({@code html}, {@code markdown} or null for plain
 * text) and an optional inline keyboard (rows of buttons).
 */
public record MaxOutgoingMessage(String text, String format, List<List<MaxButton>> keyboard, boolean notifyGuest) {

    public MaxOutgoingMessage {
        text = text == null ? "" : text;
        keyboard = keyboard == null ? List.of() : keyboard.stream().map(List::copyOf).toList();
    }

    public static MaxOutgoingMessage plain(String text) {
        return new MaxOutgoingMessage(text, null, List.of(), true);
    }

    public boolean hasKeyboard() {
        return !keyboard.isEmpty();
    }
}
