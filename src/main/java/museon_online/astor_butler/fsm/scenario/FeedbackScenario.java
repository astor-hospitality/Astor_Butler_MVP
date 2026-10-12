package museon_online.astor_butler.fsm.scenario;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.billing.VisitReviewService;
import museon_online.astor_butler.domain.feedback.FeedbackService;
import museon_online.astor_butler.domain.feedback.GuestFeedback;
import museon_online.astor_butler.domain.feedback.GuestFeedbackCommand;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.i18n.GuestTexts;
import museon_online.astor_butler.i18n.LanguageTags;
import museon_online.astor_butler.i18n.LocalizedText;
import museon_online.astor_butler.i18n.ResolvedLocale;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Guest feedback: ask for the text, pass it to the team, thank the guest.
 *
 * <p>Also the worked example of the guest-language layer: guest texts come from {@code i18n/guest/*.yaml} through
 * {@link GuestTexts}, the reply-keyboard label is recognised in every catalog language, and the staff alert names
 * the guest's language. With {@code astor.i18n.enabled=false} every text is the Russian one, as before.
 */
@Component
@Slf4j
public class FeedbackScenario implements FsmScenario {

    static final String TEXT_BUTTON = "feedback.button";
    static final String TEXT_ASK = "feedback.ask_text";
    static final String TEXT_THANKS = "feedback.thanks";
    static final String TEXT_TIP_OFFER = "feedback.tip_offer";
    /**
     * Russian like the main menu and the rating buttons, whatever the guest's language: the tip scenario knows the
     * guest wants it by the word "чаевые".
     */
    static final List<List<String>> TIP_OFFER_ROWS = List.of(List.of("Оставить чаевые"), List.of("Главное меню"));

    private final FSMStorage fsmStorage;
    private final FeedbackService feedbackService;
    private final GuestTexts guestTexts;

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;

    /** Ties a comment the guest asked to write after rating a visit back to that visit; absent outside Spring. */
    @Autowired(required = false)
    private VisitReviewService visitReviews;

    @Autowired
    public FeedbackScenario(FSMStorage fsmStorage, FeedbackService feedbackService, GuestTexts guestTexts) {
        this.fsmStorage = fsmStorage;
        this.feedbackService = feedbackService;
        this.guestTexts = guestTexts == null ? GuestTexts.russianOnly() : guestTexts;
    }

    /** Without Spring: Russian texts only, exactly as before the guest-language layer. */
    public FeedbackScenario(FSMStorage fsmStorage, FeedbackService feedbackService) {
        this(fsmStorage, feedbackService, GuestTexts.russianOnly());
    }

    @Override
    public String id() {
        return "FEEDBACK";
    }

    @Override
    public int priority() {
        return 36;
    }

    @Override
    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        return owns(state) || isFeedbackIntent(normalized) || guestTexts.matchesLabel(TEXT_BUTTON, text);
    }

    @Override
    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        ResolvedLocale locale = guestTexts.locale(incoming);
        if (state == BotState.FEEDBACK_COLLECT_TEXT) {
            return sendFeedback(incoming, currentState, text, "FEEDBACK_TEXT_RECEIVED", locale);
        }
        if (isShortFeedbackCall(normalized) || guestTexts.matchesLabel(TEXT_BUTTON, text, locale)) {
            fsmStorage.setState(incoming.chatId(), BotState.FEEDBACK_COLLECT_TEXT);
            LocalizedText ask = guestTexts.text(locale, TEXT_ASK);
            return OutgoingMessage.of(
                    incoming,
                    ask.text(),
                    BotState.FEEDBACK_COLLECT_TEXT.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("FEEDBACK", "ASK_FEEDBACK_TEXT")
            ).withMetadata(withLanguage(Map.of("scenario", id()), ask));
        }
        return sendFeedback(incoming, currentState, text, "FEEDBACK_DIRECT_TEXT", locale);
    }

    @Override
    public boolean owns(BotState state) {
        BotState canonical = state == null ? BotState.UNKNOWN : state.canonical();
        return canonical == BotState.FEEDBACK_COLLECT_TEXT || canonical == BotState.FEEDBACK_SENT;
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    private OutgoingMessage sendFeedback(
            IncomingMessage incoming,
            BotState previousState,
            String text,
            String reasonAction,
            ResolvedLocale locale
    ) {
        GuestFeedback feedback = feedbackService.create(feedbackCommand(incoming, previousState, text));
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        LocalizedText thanks = guestTexts.text(locale, TEXT_THANKS);
        Optional<VisitReviewService.CommentLink> visit = ratedVisit(incoming, feedback);
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
                "scenario", id(),
                "feedbackId", feedback.id(),
                "feedbackType", feedback.feedbackType().name(),
                "sentiment", feedback.sentiment().name(),
                "priority", feedback.priority().name(),
                "handoffReason", reasonAction
        ));
        visit.ifPresent(link -> metadata.put("visitReviewBillId", link.billId()));
        List<String> actions = new ArrayList<>(List.of("FEEDBACK", reasonAction, "ADMIN_ALERT", "RETURN_MAIN_MENU"));
        String reply = thanks.text();
        if (visit.map(VisitReviewService.CommentLink::offerTip).orElse(false)) {
            // The guest went from the stars to the comment, past the tip button; the tip is offered once more.
            reply = reply + "\n\n" + guestTexts.text(locale, TEXT_TIP_OFFER).text();
            metadata.put("replyKeyboardRows", TIP_OFFER_ROWS);
            actions.add("TIP_OFFERED");
        }
        return OutgoingMessage.of(
                incoming,
                reply,
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                adminAlert(incoming, previousState, text, feedback, locale, visit),
                actions
        ).withMetadata(withLanguage(metadata, thanks));
    }

    /** The visit the guest rated, when this is the comment they asked to write after the stars. */
    private Optional<VisitReviewService.CommentLink> ratedVisit(IncomingMessage incoming, GuestFeedback feedback) {
        if (visitReviews == null || feedback == null) {
            return Optional.empty();
        }
        try {
            return visitReviews.attachComment(incoming.chatId(), feedback.id());
        } catch (RuntimeException e) {
            // The comment still reaches the team; only its tie to the rated visit is lost.
            log.warn("Feedback #{} was not tied to a rated visit: {}", feedback.id(), e.toString());
            return Optional.empty();
        }
    }

    /** "Комментарий к оценке визита: ★★★★☆, заявка #77" for the team; empty for any other feedback. */
    private static String ratedVisitLine(Optional<VisitReviewService.CommentLink> visit) {
        if (visit.isEmpty()) {
            return "";
        }
        VisitReviewService.CommentLink link = visit.get();
        String stars = link.stars() == null ? "без звёзд" : "★".repeat(link.stars()) + "☆".repeat(Math.max(0, 5 - link.stars()));
        String reservation = link.tableReservationId() == null ? "" : ", заявка #" + link.tableReservationId();
        return "\nКомментарий к оценке визита: " + stars + reservation;
    }

    private Map<String, Object> withLanguage(Map<String, Object> metadata, LocalizedText text) {
        Map<String, Object> language = guestTexts.metadata(text);
        if (language.isEmpty()) {
            return metadata;
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(language);
        return merged;
    }

    /** Staff read Russian; the line appears only when the guest is answered in another language. */
    private String guestLanguageLine(ResolvedLocale locale) {
        if (!guestTexts.enabled() || locale == null || locale.is(guestTexts.defaultLanguage())) {
            return "";
        }
        return "\nЯзык гостя: %s (%s)".formatted(html(LanguageTags.russianName(locale.language())), html(locale.language()));
    }

    private GuestFeedbackCommand feedbackCommand(IncomingMessage incoming, BotState previousState, String text) {
        return new GuestFeedbackCommand(
                incoming.chatId(),
                incoming.telegramUserId(),
                null,
                "AERIS",
                displayName(incoming),
                text,
                previousState == null ? null : previousState.name(),
                incoming.correlationId(),
                adminChatId
        );
    }

    private AdminAlert adminAlert(
            IncomingMessage incoming,
            BotState previousState,
            String text,
            GuestFeedback feedback,
            ResolvedLocale locale,
            Optional<VisitReviewService.CommentLink> visit
    ) {
        if (adminChatId == null || adminChatId.isBlank()) {
            return AdminAlert.none();
        }
        String guestLink = incoming.telegramUserId() == null
                ? html(text(incoming.chatId()))
                : "<a href=\"tg://user?id=%s\">%s</a>".formatted(
                html(text(incoming.telegramUserId())),
                html(displayName(incoming))
        );
        String body = """
                <b>Astor Butler / feedback</b>
                Гость оставил отзыв

                <b>Гость</b>
                %s
                chat %s / user %s%s%s

                <b>Отзыв</b>
                <blockquote>%s</blockquote>

                <b>Запись в системе</b>
                Feedback: #%s
                Type: %s
                Sentiment: %s
                Priority: %s
                Status: %s

                <b>Контекст</b>
                Previous state: %s
                Scenario: FEEDBACK%s

                <b>Действие</b>
                Проверьте карточку feedback #%s. Если это жалоба, высокий приоритет или нужен персональный follow-up, ответьте гостю вручную и закройте запись после решения.

                <b>Техника</b>
                Channel: %s
                Correlation: %s
                """.formatted(
                guestLink,
                html(text(incoming.chatId())),
                html(text(incoming.telegramUserId())),
                incoming.username() == null || incoming.username().isBlank() ? "" : " / @" + html(incoming.username()),
                guestLanguageLine(locale),
                html(blankAsEmptyLabel(text)),
                html(text(feedback.id())),
                html(text(feedback.feedbackType())),
                html(text(feedback.sentiment())),
                html(text(feedback.priority())),
                html(text(feedback.status())),
                html(text(previousState)),
                html(ratedVisitLine(visit)),
                html(text(feedback.id())),
                html(text(incoming.channel())),
                html(blankAsEmptyLabel(incoming.correlationId()))
        );
        return new AdminAlert(true, adminChatId, body);
    }

    private boolean isFeedbackIntent(String text) {
        return containsAny(text, "отзыв", "feedback", "обратная связь", "впечатлен", "впечатление", "оценка завед");
    }

    private boolean isShortFeedbackCall(String text) {
        return text.equals("отзыв")
                || text.equals("оставить отзыв")
                || text.equals("/feedback")
                || text.equals("feedback")
                || text.equals("обратная связь");
    }

    private boolean containsAny(String text, String... variants) {
        for (String variant : variants) {
            if (text.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private String displayName(IncomingMessage incoming) {
        String firstName = normalizeDisplay(incoming.firstName());
        String lastName = normalizeDisplay(incoming.lastName());
        String username = normalizeDisplay(incoming.username());
        String fullName = (firstName + " " + lastName).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (!username.isBlank()) {
            return "@" + username;
        }
        return "unknown";
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplay(String text) {
        return text == null ? "" : text.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String blankAsEmptyLabel(String value) {
        String normalized = normalizeDisplay(value);
        return normalized.isBlank() ? "(empty)" : normalized;
    }

    private String html(String value) {
        return text(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
