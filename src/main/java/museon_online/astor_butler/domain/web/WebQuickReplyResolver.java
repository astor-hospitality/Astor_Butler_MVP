package museon_online.astor_butler.domain.web;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns an FSM reply into web quick replies using the same precedence the Telegram transport uses
 * ({@code TelegramRouter.send()}): contact request, then an explicit {@code metadata.replyKeyboardRows},
 * then the guest main menu for {@code READY_FOR_DIALOG}, otherwise no buttons.
 * The Telegram package is not touched: the main menu is duplicated here on purpose and trimmed to what a browser can do.
 */
@Component
public class WebQuickReplyResolver {

    public static final String CONTACT_LABEL = "Поделиться номером";

    /** Telegram's guest main menu minus flows that need Telegram payments (tips, donations, auction, merch). */
    static final String DEFAULT_MAIN_MENU = "Бронь стола,Меню кухни,Бар,Коктейли,Винная карта,Афиша,Видео-тур,Сабраж,"
            + "Концепция AERIS,Связаться с командой,Изменить / отменить,Оставить отзыв,Главное меню";

    private final List<String> mainMenu;

    public WebQuickReplyResolver(@Value("${astor.web.main-menu:" + DEFAULT_MAIN_MENU + "}") String mainMenu) {
        this.mainMenu = csv(mainMenu);
    }

    public List<WebQuickReply> resolve(OutgoingMessage outgoing) {
        if (outgoing == null) {
            return List.of();
        }
        if (outgoing.requestContact()) {
            return List.of(WebQuickReply.contact(CONTACT_LABEL));
        }
        List<WebQuickReply> custom = fromReplyKeyboardRows(outgoing);
        if (!custom.isEmpty()) {
            return custom;
        }
        if (BotState.READY_FOR_DIALOG.name().equals(outgoing.nextState())) {
            return mainMenu.stream().map(WebQuickReply::text).toList();
        }
        return List.of();
    }

    private List<WebQuickReply> fromReplyKeyboardRows(OutgoingMessage outgoing) {
        if (outgoing.metadata() == null) {
            return List.of();
        }
        Object rawRows = outgoing.metadata().get("replyKeyboardRows");
        if (!(rawRows instanceof List<?> rows)) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (Object row : rows) {
            if (row instanceof List<?> cells) {
                cells.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(String::trim)
                        .filter(label -> !label.isBlank())
                        .forEach(labels::add);
            } else if (row instanceof String label && !label.isBlank()) {
                labels.add(label.trim());
            }
        }
        List<WebQuickReply> result = new ArrayList<>();
        labels.forEach(label -> result.add(WebQuickReply.text(label)));
        return result;
    }

    private static List<String> csv(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
