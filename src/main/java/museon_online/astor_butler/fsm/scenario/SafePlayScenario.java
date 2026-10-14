package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.media.AerisMediaCatalog;
import museon_online.astor_butler.domain.media.MediaAsset;
import museon_online.astor_butler.domain.semantic.SemanticRetrievalService;
import museon_online.astor_butler.domain.semantic.SemanticSearchResult;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.reply.ScenarioReply;
import museon_online.astor_butler.fsm.reply.ScenarioReplyComposer;
import museon_online.astor_butler.fsm.reply.ScenarioReplyDraft;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SafePlayScenario implements FsmScenario {

    private final FSMStorage fsmStorage;
    private final TableReservationRepository tableReservationRepository;
    private final AerisMediaCatalog mediaCatalog;
    private final SemanticRetrievalService semanticRetrievalService;
    private final ScenarioReplyComposer replyComposer;

    /** The guest journey ledger (spec 2026-10-13): optional, silent while the feature is off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents;

    private void journey(IncomingMessage incoming, museon_online.astor_butler.domain.journey.VisitEventKind kind, String note) {
        if (journeyEvents != null && incoming != null) {
            journeyEvents.onChat(incoming.chatId(), kind, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST, note);
        }
    }

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;

    @Override
    public String id() {
        return "SAFE_PLAY";
    }

    @Override
    public int priority() {
        return 37;
    }

    @Override
    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        if (!owns(state) && isMerchChainPurchaseIntent(normalized)) {
            return false;
        }
        return owns(state) || isSafePlayIntent(normalized);
    }

    @Override
    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        String normalized = normalize(text);
        if (isDangerousHowTo(normalized)) {
            return refuseDangerousHowTo(incoming, currentState, text);
        }
        if (isSabrageWineAdvice(normalized)) {
            return sabrageWineAdvice(incoming, text);
        }
        if (state == BotState.SAFE_PLAY_COLLECT_DETAILS) {
            return sendTeamRequest(incoming, currentState, text, "SAFE_PLAY_DETAILS_RECEIVED");
        }
        if (isShortSafePlayCall(normalized)) {
            journey(incoming, museon_online.astor_butler.domain.journey.VisitEventKind.CHOICE_THEATRE, null);
            fsmStorage.setState(incoming.chatId(), BotState.SAFE_PLAY_COLLECT_DETAILS);
            return OutgoingMessage.of(
                    incoming,
                    "Могу передать команде запрос на безопасный сабражный ритуал. Напишите, пожалуйста: стол/время, игристое или шампанское, и хотите ли фото/видео-память момента.",
                    BotState.SAFE_PLAY_COLLECT_DETAILS.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("SAFE_PLAY", "ASK_SAFE_PLAY_DETAILS", "NO_DANGEROUS_HOW_TO")
            ).withMetadata(Map.of("scenario", id()));
        }
        return sendTeamRequest(incoming, currentState, text, "SAFE_PLAY_DIRECT_REQUEST");
    }

    @Override
    public boolean owns(BotState state) {
        BotState canonical = state == null ? BotState.UNKNOWN : state.canonical();
        return canonical == BotState.SAFE_PLAY_COLLECT_DETAILS
                || canonical == BotState.SAFE_PLAY_WAIT_TEAM_CONFIRMATION;
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    private OutgoingMessage refuseDangerousHowTo(IncomingMessage incoming, BotState previousState, String text) {
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                "Я не даю инструкции по опасным трюкам. Могу предложить безопасный сабражный ритуал в AERIS: его выполняет обученная команда, а вы участвуете как гость опыта.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                adminAlert(incoming, previousState, text, "SAFE_PLAY_SAFETY_REFUSAL"),
                List.of("SAFE_PLAY", "NO_DANGEROUS_HOW_TO", "OFFER_TEAM_RITUAL", "ADMIN_ALERT", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of(
                "scenario", id(),
                "safetyBoundary", "NO_DANGEROUS_HOW_TO"
        ));
    }

    private OutgoingMessage sabrageWineAdvice(IncomingMessage incoming, String text) {
        List<SemanticSearchResult> ragContext = semanticRetrievalService.search(
                "AERIS",
                text,
                List.of("AERIS_MENU_WINE_SOURCE", "AERIS_SAFE_PLAY_SOURCE"),
                5
        );
        MediaAsset wineMenu = mediaCatalog.wineMenu();
        String fallbackText = """
                Под сабраж я бы смотрел игристое или шампанское бутылкой: ритуал выполняет только команда AERIS, а я помогу выбрать стиль и бюджет.

                Из актуальной винной карты:
                • Mont Marcal Cava Brut — 5 500
                • Tenuta Dodici 12 Prosecco — 3 500
                • Cuvée Françoise Rosé Crémant de Limoux — 6 500
                • Bernard Remy Champagne — 9 900
                • Moët & Chandon Brut Imperial — 17 000

                Для торжественного момента я бы начал с Bernard Remy или Moët & Chandon; для легкого игрового старта — Cava или Prosecco. Наличие бутылки команда подтвердит перед ритуалом.
                """;
        ScenarioReply reply = replyComposer.compose(ScenarioReplyDraft.of(
                incoming,
                "AERIS",
                id(),
                BotState.READY_FOR_DIALOG.name(),
                "SAFE_PLAY_WINE_ADVICE",
                text,
                fallbackText,
                ragContext
        ));
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenario", id());
        metadata.put("contentKind", "SABRAGE_WINE_ADVICE");
        metadata.put("documentAssetCode", wineMenu.assetCode());
        metadata.put("documentObjectKey", wineMenu.objectKey());
        metadata.put("documentFilename", wineMenu.filename());
        metadata.put("documentCaption", wineMenu.title());
        metadata.put("ragContext", ragContext.stream().map(this::ragMetadata).toList());
        metadata.put("replyGenerated", reply.generated());
        metadata.put("replyProvider", reply.provider());
        metadata.put("replyModel", reply.model());
        metadata.put("safetyBoundary", "NO_DANGEROUS_HOW_TO");
        return OutgoingMessage.of(
                incoming,
                reply.text(),
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("SAFE_PLAY", "SABRAGE_WINE_ADVICE", "RAG_CONTEXT_USED", "NO_DANGEROUS_HOW_TO", "RETURN_MAIN_MENU")
        ).withMetadata(metadata);
    }

    private OutgoingMessage sendTeamRequest(
            IncomingMessage incoming,
            BotState previousState,
            String text,
            String reasonAction
    ) {
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                "Передал запрос команде AERIS. Сабражный ритуал возможен только после проверки staff availability, бутылки, времени и безопасности площадки.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                adminAlert(incoming, previousState, text, reasonAction),
                List.of("SAFE_PLAY", reasonAction, "TEAM_CONFIRMATION_REQUIRED", "NO_DANGEROUS_HOW_TO", "ADMIN_ALERT", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of(
                "scenario", id(),
                "safetyBoundary", "TEAM_PERFORMS_RITUAL",
                "merchLink", "SABRAGE_CHAIN_OPTIONAL"
        ));
    }

    private AdminAlert adminAlert(IncomingMessage incoming, BotState previousState, String text, String action) {
        if (adminChatId == null || adminChatId.isBlank()) {
            return AdminAlert.none();
        }
        String body = """
                <b>Astor Butler / safe play</b>
                Гость запросил сабражный ритуал или игровой hospitality-момент

                <b>Гость</b>
                %s
                chat %s / user %s%s

                <b>Запрос</b>
                <blockquote>%s</blockquote>

                <b>Safety boundary</b>
                Не давать гостю dangerous how-to. Ритуал выполняет только обученная команда после проверки условий.

                <b>Контекст</b>
                Previous state: %s
                Scenario: SAFE_PLAY
                Action: %s
                %s

                <b>Действие</b>
                Проверьте стол/время, бутылку, staff availability и возможность ритуала. Нажмите Да/Нет ниже. При интересе к цепи передайте в merch flow.

                <b>Техника</b>
                Channel: %s
                Correlation: %s
                """.formatted(
                html(displayName(incoming)),
                html(text(incoming.chatId())),
                html(text(incoming.telegramUserId())),
                incoming.username() == null || incoming.username().isBlank() ? "" : " / @" + html(incoming.username()),
                html(blankAsEmptyLabel(text)),
                html(text(previousState)),
                html(action),
                html(bookingContext(incoming)),
                html(text(incoming.channel())),
                html(blankAsEmptyLabel(incoming.correlationId()))
        );
        return new AdminAlert(true, adminChatId, body, List.of(List.of(
                new AdminAlert.Button("Да, можно", "safe_play:approve:" + incoming.chatId()),
                new AdminAlert.Button("Нет", "safe_play:reject:" + incoming.chatId())
        )));
    }

    private String bookingContext(IncomingMessage incoming) {
        if (incoming == null || incoming.chatId() == null) {
            return "Активная бронь: не найдена";
        }
        return tableReservationRepository.findActiveOrdersByChatId(incoming.chatId())
                .stream()
                .findFirst()
                .map(this::bookingContext)
                .orElse("Активная бронь: не найдена. Если это предзаказ, уточните стол/время у гостя.");
    }

    private String bookingContext(TableReservationOrder order) {
        return """
                Активная бронь: #%s
                Стол: %s
                Время: %s - %s
                Гостей: %s
                Пожелание: %s
                Телефон: %s
                """.formatted(
                text(order.id()),
                tableName(order),
                text(order.requestedStartAt()),
                text(order.requestedEndAt()),
                text(order.partySize()),
                blankAsEmptyLabel(order.seatingPreference()),
                blankAsEmptyLabel(order.guestPhone())
        ).strip();
    }

    private String tableName(TableReservationOrder order) {
        if (order.tableCode() == null || order.tableCode().isBlank()) {
            return "не выбран";
        }
        if (order.tableDisplayName() == null || order.tableDisplayName().isBlank()) {
            return order.tableCode();
        }
        return order.tableDisplayName() + " (" + order.tableCode() + ")";
    }

    private boolean isSafePlayIntent(String text) {
        return containsAny(text, "сабраж", "sabrage", "открыть шампан", "открыть игрист", "ритуал", "игровой момент", "33 сабраж");
    }

    private boolean isSabrageWineAdvice(String text) {
        return containsAny(text, "сабраж", "sabrage")
                && containsAny(text, "вино", "игрист", "шампан", "бутыл")
                && (containsAny(text, "какое", "какой", "что взять", "подобрать", "цена", "стоимость")
                || text.contains(" под "));
    }

    private boolean isShortSafePlayCall(String text) {
        return text.equals("сабраж")
                || text.equals("хочу сабраж")
                || text.equals("сабражный ритуал")
                || text.equals("ритуал");
    }

    private boolean isDangerousHowTo(String text) {
        return containsAny(text, "научи", "как сделать", "как открыть", "самому", "дома", "инструкция", "объясни технику");
    }

    private boolean isMerchChainPurchaseIntent(String text) {
        return containsAny(text, "цепь", "цепочку", "цепи")
                && containsAny(text, "купить", "заказать", "предзаказ", "подарок", "мерч", "стоимость", "цена");
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

    private Map<String, Object> ragMetadata(SemanticSearchResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceCode", result.sourceCode());
        metadata.put("sourceType", result.sourceType());
        metadata.put("title", result.title());
        metadata.put("score", result.score());
        metadata.put("content", result.shortContent(360));
        return metadata;
    }

    private String html(String value) {
        return text(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
