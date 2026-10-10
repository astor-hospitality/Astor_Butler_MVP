package museon_online.astor_butler.max.adapter;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.max.client.MaxButton;
import museon_online.astor_butler.max.client.MaxOutgoingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the gateway's {@link OutgoingMessage} into MAX messages, mirroring what the Telegram router does with the
 * same object, so both channels show the guest the same choices:
 *
 * <ul>
 *   <li>{@code requestContact} in a dialog: a {@code request_contact} button with the Telegram wording (consent);</li>
 *   <li>{@code metadata.replyKeyboardRows}: the scenario's own rows as {@code message} buttons;</li>
 *   <li>{@code nextState = READY_FOR_DIALOG}: the guest main menu as {@code message} buttons;</li>
 *   <li>{@code removeKeyboard}: nothing to remove, MAX keyboards belong to a single message.</li>
 * </ul>
 *
 * <p>MAX has no persistent reply keyboard, so a {@code message} button (it posts its label into the chat as the
 * guest's own words) is the closest equivalent: the FSM receives exactly the text it receives from Telegram.
 * Text longer than the MAX limit is split on paragraph breaks; the keyboard goes under the last part.
 */
public class MaxReplyRenderer {

    /** MAX rejects message text above 4000 characters. */
    public static final int MAX_TEXT_LENGTH = 4000;
    /** Inline keyboard limits: 7 buttons per row, 30 rows, 210 buttons, 128 characters per label. */
    static final int MAX_BUTTONS_PER_ROW = 7;
    static final int MAX_ROWS = 30;
    static final int MAX_BUTTONS = 210;
    static final int MAX_LABEL_LENGTH = 128;
    static final String CONTACT_BUTTON = "Согласиться и поделиться контактом";

    /**
     * The guest main menu, the same labels and order as the Telegram reply keyboard (TelegramRouter). Kept as a
     * copy in phase 1 so the Telegram code is not touched; phase 2 moves both onto one shared catalog.
     */
    static final List<List<String>> GUEST_MAIN_MENU = List.of(
            List.of("Меню кухни", "Бар"),
            List.of("Коктейли", "Винная карта"),
            List.of("Видео-тур", "Бронь стола"),
            List.of("Связаться с командой", "Главное меню"),
            List.of("Сабраж", "Афиша"),
            List.of("Концепция AERIS"),
            List.of("Изменить / отменить", "Оставить отзыв"),
            List.of("Чаевые", "Донат"),
            List.of("Аукцион", "Мерч")
    );

    /** The messages to send for one gateway answer, in order; empty when the gateway chose to stay silent. */
    public List<MaxOutgoingMessage> render(OutgoingMessage outgoing, boolean dialog) {
        if (outgoing == null || outgoing.text() == null || outgoing.text().isBlank()) {
            return List.of();
        }
        String format = outgoing.html() ? "html" : null;
        List<List<MaxButton>> keyboard = withinLimits(keyboard(outgoing, dialog));
        List<String> parts = split(outgoing.text().strip(), MAX_TEXT_LENGTH);
        List<MaxOutgoingMessage> messages = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1;
            messages.add(new MaxOutgoingMessage(parts.get(i), format, last ? keyboard : List.of(), true));
        }
        return messages;
    }

    List<List<MaxButton>> keyboard(OutgoingMessage outgoing, boolean dialog) {
        if (outgoing.requestContact() && dialog) {
            return List.of(List.of(MaxButton.requestContact(CONTACT_BUTTON)));
        }
        List<List<MaxButton>> custom = customRows(outgoing.metadata());
        if (!custom.isEmpty()) {
            return custom;
        }
        if (dialog && BotState.READY_FOR_DIALOG.name().equals(outgoing.nextState()) && !outgoing.requestContact()) {
            return GUEST_MAIN_MENU.stream()
                    .map(row -> row.stream().map(MaxButton::message).toList())
                    .toList();
        }
        return List.of();
    }

    /**
     * Keeps a keyboard inside the MAX limits instead of having the whole message refused: long rows wrap, labels
     * that are too long are dropped (a cut label would no longer be the words the FSM expects), extra rows go.
     */
    static List<List<MaxButton>> withinLimits(List<List<MaxButton>> keyboard) {
        List<List<MaxButton>> rows = new ArrayList<>();
        int total = 0;
        for (List<MaxButton> row : keyboard) {
            List<MaxButton> fitting = row.stream()
                    .filter(button -> button.text() != null && !button.text().isBlank()
                            && button.text().length() <= MAX_LABEL_LENGTH)
                    .toList();
            for (int from = 0; from < fitting.size(); from += MAX_BUTTONS_PER_ROW) {
                List<MaxButton> chunk = fitting.subList(from, Math.min(fitting.size(), from + MAX_BUTTONS_PER_ROW));
                if (rows.size() == MAX_ROWS || total + chunk.size() > MAX_BUTTONS) {
                    return rows;
                }
                rows.add(List.copyOf(chunk));
                total += chunk.size();
            }
        }
        return rows;
    }

    private List<List<MaxButton>> customRows(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("replyKeyboardRows");
        if (!(raw instanceof List<?> rows) || rows.isEmpty()) {
            return List.of();
        }
        List<List<MaxButton>> keyboard = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof List<?> labels)) {
                continue;
            }
            List<MaxButton> buttons = labels.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(label -> !label.isBlank())
                    .map(MaxButton::message)
                    .toList();
            if (!buttons.isEmpty()) {
                keyboard.add(buttons);
            }
        }
        return keyboard;
    }

    /** Splits on blank lines, then on line breaks, and only cuts inside a line when one line is itself too long. */
    static List<String> split(String text, int limit) {
        if (text.length() <= limit) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\n")) {
            for (String piece : fit(paragraph, limit)) {
                String separator = current.isEmpty() ? "" : "\n\n";
                if (current.length() + separator.length() + piece.length() > limit) {
                    parts.add(current.toString());
                    current.setLength(0);
                    separator = "";
                }
                current.append(separator).append(piece);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static List<String> fit(String paragraph, int limit) {
        if (paragraph.length() <= limit) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : paragraph.split("\n")) {
            String rest = line;
            while (rest.length() > limit) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.add(rest.substring(0, limit));
                rest = rest.substring(limit);
            }
            String separator = current.isEmpty() ? "" : "\n";
            if (current.length() + separator.length() + rest.length() > limit) {
                pieces.add(current.toString());
                current.setLength(0);
                separator = "";
            }
            current.append(separator).append(rest);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }
}
