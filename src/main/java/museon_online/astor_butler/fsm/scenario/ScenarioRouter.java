package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.semantic.IntentExampleRepository;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.fsm.understanding.InputIntent;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScenarioRouter {

    private final FSMStorage fsmStorage;
    private final FirstTouchScenario firstTouchScenario;
    private final BusinessLunchScenario businessLunchScenario;
    private final TableBookingScenario tableBookingScenario;
    private final EventBookingScenario eventBookingScenario;
    private final ChangeCancelScenario changeCancelScenario;
    private final ManagerHelpScenario managerHelpScenario;
    private final FeedbackScenario feedbackScenario;
    private final PreferenceScenario preferenceScenario;
    private final ConciergeScenario conciergeScenario;
    private final SafePlayScenario safePlayScenario;
    private final MerchScenario merchScenario;
    private final MenuAssetsScenario menuAssetsScenario;
    private final QuietGuideScenario quietGuideScenario;
    private final ImpactMeterScenario impactMeterScenario;
    private final SmartTipScenario smartTipScenario;
    private final HiddenHeartScenario hiddenHeartScenario;
    private final ArtAuctionScenario artAuctionScenario;
    private final MainMenuScenario mainMenuScenario;
    private final RecoveryScenario recoveryScenario;
    private final GuestInputUnderstandingService inputUnderstandingService;
    private final IntentExampleRepository intentExampleRepository;

    public OutgoingMessage route(IncomingMessage incoming, BotState currentState, String text) {
        // A guest the Concierge sent for a business lunch may still have to give consent: the lunch waits and then goes on.
        businessLunchScenario.rememberHandoff(incoming, text);
        if (firstTouchScenario.supports(incoming, currentState, text)) {
            return businessLunchScenario.continueAfterFirstTouch(incoming, firstTouchScenario.handle(incoming, currentState, text));
        }

        // Its own steps and its own entry phrases, decided before any intent guessing, so that an answer such as "13:00"
        // or "на двоих" cannot be taken for the start of a table booking.
        if (businessLunchScenario.supports(incoming, currentState, text)) {
            return businessLunchScenario.handle(incoming, currentState, text);
        }

        OutgoingMessage keyboardShortcut = tryKeyboardShortcutRoute(incoming, currentState, text);
        if (keyboardShortcut != null) {
            return keyboardShortcut;
        }

        UnderstoodInput understood = inputUnderstandingService.understand(text, currentState, understandingContext(incoming));
        captureUnderstandingMissIfNeeded(incoming, currentState, understood);
        String routeText = understood.routeText();
        OutgoingMessage composite = tryCompositeIntent(incoming, currentState, routeText, understood);
        if (composite != null) {
            return withUnderstandingMetadata(composite, understood);
        }

        OutgoingMessage primaryIntentRoute = tryPrimaryIntentRoute(incoming, currentState, routeText, understood);
        if (primaryIntentRoute != null) {
            return primaryIntentRoute;
        }

        for (FsmScenario scenario : orderedRuntimeScenarios()) {
            if (scenario.supports(incoming, currentState, routeText, understood)) {
                OutgoingMessage outgoing = withUnderstandingMetadata(
                        withExecutablePendingContent(incoming, scenario.handle(incoming, currentState, routeText, understood)),
                        understood
                );
                captureRecoveryMissIfNeeded(incoming, currentState, understood, outgoing);
                return outgoing;
            }
        }
        return null;
    }

    private OutgoingMessage tryKeyboardShortcutRoute(IncomingMessage incoming, BotState currentState, String text) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state != BotState.READY_FOR_DIALOG && state != BotState.AI_FALLBACK) {
            return null;
        }
        String normalized = normalize(text);
        FsmScenario scenario = switch (normalized) {
            case "меню кухни", "бар", "коктейли", "винная карта" -> menuAssetsScenario;
            case "видео-тур" -> quietGuideScenario;
            case "бронь", "бронь стола" -> tableBookingScenario;
            case "связаться с командой", "помощь команды" -> managerHelpScenario;
            case "главное меню" -> mainMenuScenario;
            default -> null;
        };
        if (scenario == null || !scenario.supports(incoming, state, text)) {
            return null;
        }
        return withExecutablePendingContent(incoming, scenario.handle(incoming, state, text));
    }

    private Map<String, Object> understandingContext(IncomingMessage incoming) {
        if (incoming == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("channel", incoming.channel() == null ? "" : incoming.channel().name());
        context.put("chatId", incoming.chatId());
        context.put("telegramUserId", incoming.telegramUserId());
        context.put("correlationId", incoming.correlationId());
        return context;
    }

    private OutgoingMessage tryPrimaryIntentRoute(
            IncomingMessage incoming,
            BotState currentState,
            String routeText,
            UnderstoodInput understood
    ) {
        if (understood == null || understood.needsClarification() || understood.confidence() < 0.70) {
            return null;
        }
        FsmScenario scenario = scenarioForPrimaryIntent(understood.primaryIntent());
        if (scenario == null) {
            return null;
        }
        if (!scenario.supports(incoming, currentState, routeText, understood)) {
            return null;
        }
        OutgoingMessage outgoing = withUnderstandingMetadata(
                withExecutablePendingContent(incoming, scenario.handle(incoming, currentState, routeText, understood)),
                understood
        );
        captureRecoveryMissIfNeeded(incoming, currentState, understood, outgoing);
        return outgoing;
    }

    private FsmScenario scenarioForPrimaryIntent(InputIntent intent) {
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case TABLE_BOOKING -> tableBookingScenario;
            case EVENT_BOOKING -> eventBookingScenario;
            case CHANGE_CANCEL -> changeCancelScenario;
            case MANAGER_HELP -> managerHelpScenario;
            case FEEDBACK -> feedbackScenario;
            case SAFE_PLAY -> safePlayScenario;
            case MERCH -> merchScenario;
            case MENU_ASSETS -> menuAssetsScenario;
            case QUIET_GUIDE -> quietGuideScenario;
            case SMART_TIP -> smartTipScenario;
            case HIDDEN_HEART -> hiddenHeartScenario;
            case ART_AUCTION -> artAuctionScenario;
            case MAIN_MENU -> mainMenuScenario;
            default -> null;
        };
    }

    private void captureUnderstandingMissIfNeeded(IncomingMessage incoming, BotState currentState, UnderstoodInput understood) {
        if (incoming == null || understood == null || !understood.needsClarification()) {
            return;
        }
        captureMiss(incoming, currentState, understood);
    }

    private void captureRecoveryMissIfNeeded(
            IncomingMessage incoming,
            BotState currentState,
            UnderstoodInput understood,
            OutgoingMessage outgoing
    ) {
        if (incoming == null || understood == null || understood.needsClarification() || outgoing == null || outgoing.actions() == null) {
            return;
        }
        if (!outgoing.actions().contains("RECOVERY")) {
            return;
        }
        captureMiss(incoming, currentState, understood);
    }

    private void captureMiss(IncomingMessage incoming, BotState currentState, UnderstoodInput understood) {
        try {
            intentExampleRepository.captureMiss(
                    "AERIS",
                    incoming.chatId(),
                    incoming.telegramUserId(),
                    stateName(currentState),
                    understood.rawText(),
                    understood.primaryIntent().name(),
                    understood.confidence()
            );
        } catch (RuntimeException ex) {
            log.warn("Intent understanding miss was not stored: {}", ex.toString());
        }
    }

    private String stateName(BotState state) {
        return state == null ? BotState.UNKNOWN.name() : state.canonical().name();
    }

    private List<FsmScenario> orderedRuntimeScenarios() {
        return List.of(
                tableBookingScenario,
                eventBookingScenario,
                changeCancelScenario,
                managerHelpScenario,
                feedbackScenario,
                preferenceScenario,
                conciergeScenario,
                safePlayScenario,
                merchScenario,
                menuAssetsScenario,
                quietGuideScenario,
                impactMeterScenario,
                smartTipScenario,
                hiddenHeartScenario,
                artAuctionScenario,
                mainMenuScenario,
                recoveryScenario
        );
    }

    private OutgoingMessage tryCompositeIntent(
            IncomingMessage incoming,
            BotState currentState,
            String text,
            UnderstoodInput understood
    ) {
        BotState state = currentState == null ? BotState.UNKNOWN : currentState.canonical();
        if (state != BotState.READY_FOR_DIALOG && state != BotState.AI_FALLBACK) {
            return null;
        }
        if (!looksComposite(text)) {
            return null;
        }

        boolean table = tableBookingScenario.supports(incoming, state, text, understood);
        boolean menu = menuAssetsScenario.supports(incoming, state, text, understood);
        boolean guide = quietGuideScenario.supports(incoming, state, text, understood);
        int intentCount = (table ? 1 : 0) + (menu ? 1 : 0) + (guide ? 1 : 0);
        if (intentCount < 2) {
            return null;
        }

        if (table) {
            OutgoingMessage primary = tableBookingScenario.handle(incoming, state, text, understood);
            List<String> pending = new ArrayList<>();
            if (menu) {
                pending.add(encodePendingIntent("MENU_ASSETS", pendingMenuPrompt(text)));
            }
            if (guide) {
                pending.add(encodePendingIntent("QUIET_GUIDE", pendingGuidePrompt(text)));
            }
            fsmStorage.setPendingIntents(incoming.chatId(), pending);
            return withAppendedText(
                    primary,
                    "После шага с бронью я смогу прислать меню, винную карту или видео-тур отдельным сообщением."
            ).withMetadata(Map.of(
                    "compositePlan", "SEQUENTIAL",
                    "primaryIntent", "TABLE_BOOKING",
                    "pendingIntents", pending.stream().map(this::pendingIntentCode).toList(),
                    "pendingIntentPrompts", pending.stream().collect(
                            java.util.stream.Collectors.toMap(
                                    this::pendingIntentCode,
                                    this::pendingIntentPrompt,
                                    (left, right) -> left,
                                    LinkedHashMap::new
                            )
                    )
            )).withMetadata(Map.of(
                    "compositeNote", "Table booking has side effects, so secondary intents are deferred until booking step is stable."
            )).withMetadata(Map.of(
                    "scenario", "CompositeIntentPlan",
                    "pendingIntentsStored", true
            ));
        }

        List<OutgoingMessage> responses = new ArrayList<>();
        if (menu) {
            responses.add(menuAssetsScenario.handle(incoming, state, text, understood));
        }
        if (guide) {
            responses.add(quietGuideScenario.handle(incoming, state, text, understood));
        }
        if (responses.size() < 2) {
            return null;
        }
        return mergeContentResponses(incoming, responses);
    }

    private OutgoingMessage mergeContentResponses(IncomingMessage incoming, List<OutgoingMessage> responses) {
        String text = responses.stream()
                .map(OutgoingMessage::text)
                .filter(part -> part != null && !part.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("Готово. Выполнил несколько действий по вашему запросу.");

        List<String> actions = new ArrayList<>();
        actions.add("COMPOSITE_INTENT_PLAN");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenario", "CompositeIntentPlan");
        metadata.put("compositePlan", "PARALLEL_CONTENT");
        List<String> completed = new ArrayList<>();
        for (OutgoingMessage response : responses) {
            actions.addAll(response.actions());
            if (response.metadata() != null) {
                mergeMetadata(metadata, response.metadata());
                Object scenario = response.metadata().get("scenario");
                if (scenario != null && !scenario.toString().isBlank()) {
                    completed.add(scenario.toString());
                }
            }
        }
        metadata.put("scenario", "CompositeIntentPlan");
        metadata.put("completedIntents", List.copyOf(completed));

        return OutgoingMessage.of(
                incoming,
                text,
                BotState.READY_FOR_DIALOG.name(),
                responses.stream().anyMatch(OutgoingMessage::html),
                false,
                true,
                false,
                AdminAlert.none(),
                List.copyOf(actions)
        ).withMetadata(metadata);
    }

    private OutgoingMessage withExecutablePendingContent(IncomingMessage incoming, OutgoingMessage primary) {
        if (!shouldExecutePending(primary)) {
            return primary;
        }

        List<String> pendingIntents = fsmStorage.getPendingIntents(incoming.chatId());
        if (pendingIntents.isEmpty()) {
            return primary;
        }

        List<OutgoingMessage> pendingResponses = new ArrayList<>();
        for (String pendingIntent : pendingIntents) {
            OutgoingMessage response = executePendingIntent(incoming, pendingIntent);
            if (response != null) {
                pendingResponses.add(response);
            }
        }
        if (pendingResponses.isEmpty()) {
            return primary;
        }

        fsmStorage.clearPendingIntents(incoming.chatId());
        List<OutgoingMessage> responses = new ArrayList<>();
        responses.add(primary);
        responses.addAll(pendingResponses);
        OutgoingMessage merged = mergeContentResponses(incoming, responses);
        return new OutgoingMessage(
                primary.channel(),
                primary.externalUserId(),
                primary.chatId(),
                merged.text(),
                primary.nextState(),
                primary.html() || merged.html(),
                primary.requestContact(),
                primary.removeKeyboard(),
                primary.fallback(),
                primary.adminAlert(),
                merged.actions(),
                merged.metadata(),
                primary.createdAt()
        ).withMetadata(Map.of(
                "compositePlan", "RESUME_PENDING_CONTENT",
                "pendingIntentsExecuted", pendingIntents.stream().map(this::pendingIntentCode).toList()
        ));
    }

    private boolean shouldExecutePending(OutgoingMessage outgoing) {
        if (outgoing == null || !BotState.READY_FOR_DIALOG.name().equals(outgoing.nextState())) {
            return false;
        }
        List<String> actions = outgoing.actions() == null ? List.of() : outgoing.actions();
        return actions.contains("TIP_DRAFT_CONFIRMED")
                || actions.contains("DONATION_DRAFT_CONFIRMED")
                || actions.contains("AUCTION_BID_GUEST_CONFIRMED")
                || actions.contains("EVENT_REQUEST_SENT")
                || actions.contains("FEEDBACK_DIRECT_TEXT")
                || actions.contains("MERCH_DIRECT_REQUEST")
                || actions.contains("SAFE_PLAY_DIRECT_REQUEST")
                || actions.contains("TABLE_BOOKING_CONFIRMED");
    }

    private OutgoingMessage executePendingIntent(IncomingMessage incoming, String pendingIntent) {
        String code = pendingIntentCode(pendingIntent);
        String prompt = pendingIntentPrompt(pendingIntent);
        if ("MENU_ASSETS".equals(code)) {
            return menuAssetsScenario.handle(incoming, BotState.READY_FOR_DIALOG, prompt);
        }
        if ("QUIET_GUIDE".equals(code)) {
            return quietGuideScenario.handle(incoming, BotState.READY_FOR_DIALOG, prompt);
        }
        return null;
    }

    private OutgoingMessage withAppendedText(OutgoingMessage message, String appendix) {
        String text = message.text() == null || message.text().isBlank()
                ? appendix
                : message.text() + "\n\n" + appendix;
        return new OutgoingMessage(
                message.channel(),
                message.externalUserId(),
                message.chatId(),
                text,
                message.nextState(),
                message.html(),
                message.requestContact(),
                message.removeKeyboard(),
                message.fallback(),
                message.adminAlert(),
                message.actions(),
                message.metadata(),
                message.createdAt()
        );
    }

    private void mergeMetadata(Map<String, Object> target, Map<String, Object> source) {
        source.forEach((key, value) -> {
            if ("documents".equals(key) && value instanceof List<?> documents) {
                List<Object> mergedDocuments = new ArrayList<>();
                Object existing = target.get("documents");
                if (existing instanceof List<?> existingDocuments) {
                    mergedDocuments.addAll(existingDocuments);
                }
                mergedDocuments.addAll(documents);
                target.put("documents", List.copyOf(mergedDocuments));
                return;
            }
            if (!"correlationId".equals(key)) {
                target.put(key, value);
            }
        });
    }

    private boolean looksComposite(String text) {
        String normalized = normalize(text);
        return normalized.contains(" и ")
                || normalized.contains(" а еще ")
                || normalized.contains(" а ещё ")
                || normalized.contains(" плюс ")
                || normalized.contains(" заодно ")
                || normalized.contains(",");
    }

    private String pendingMenuPrompt(String text) {
        String normalized = normalize(text);
        if (containsAny(normalized, "вино", "вин", "игрист", "шампан")) {
            return "покажи винную карту";
        }
        if (containsAny(normalized, "коктей", "elements", "элемент")) {
            return "покажи коктейли elements";
        }
        if (containsAny(normalized, "бар", "напит")) {
            return "покажи барную карту";
        }
        if (containsAny(normalized, "кух", "еда", "поесть")) {
            return "покажи меню кухни";
        }
        return "покажи меню";
    }

    private String pendingGuidePrompt(String text) {
        String normalized = normalize(text);
        if (containsAny(normalized, "концепц", "шеф", "георг", "матве")) {
            return "расскажи про концепцию AERIS";
        }
        if (containsAny(normalized, "афиша", "событ", "расписание", "что сегодня")) {
            return "покажи афишу";
        }
        return "покажи видео-тур";
    }

    private String encodePendingIntent(String code, String prompt) {
        return code + "::" + prompt.replace(",", " ");
    }

    private String pendingIntentCode(String pendingIntent) {
        if (pendingIntent == null || pendingIntent.isBlank()) {
            return "";
        }
        int separator = pendingIntent.indexOf("::");
        return separator < 0 ? pendingIntent.trim() : pendingIntent.substring(0, separator).trim();
    }

    private String pendingIntentPrompt(String pendingIntent) {
        if (pendingIntent == null || pendingIntent.isBlank()) {
            return "";
        }
        int separator = pendingIntent.indexOf("::");
        if (separator < 0 || separator + 2 >= pendingIntent.length()) {
            String code = pendingIntentCode(pendingIntent);
            if ("MENU_ASSETS".equals(code)) {
                return "покажи меню";
            }
            if ("QUIET_GUIDE".equals(code)) {
                return "покажи видео-тур";
            }
            return "";
        }
        return pendingIntent.substring(separator + 2).trim();
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }

    private OutgoingMessage withUnderstandingMetadata(OutgoingMessage outgoing, UnderstoodInput understood) {
        if (outgoing == null || understood == null) {
            return outgoing;
        }
        return outgoing.withMetadata(Map.of(
                "understandingIntent", understood.primaryIntent().name(),
                "understandingConfidence", understood.confidence(),
                "understandingCandidates", understood.candidates().stream().map(Enum::name).toList(),
                "understandingSlots", understood.slots().keySet().stream().toList(),
                "normalizedInput", understood.normalizedText()
        ));
    }
}
