package museon_online.astor_butler.service.message;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.timeline.FsmTimelineEvent;
import museon_online.astor_butler.domain.timeline.FsmTimelineWriter;
import museon_online.astor_butler.domain.telegram.TelegramIntakeService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.scenario.ScenarioRouter;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.kafka.UserEventProducer;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelInteractionAuditRecord;
import museon_online.astor_butler.model.ModelInteractionAuditRepository;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageGatewayService {

    private final FSMStorage fsmStorage;
    private final ModelGateway modelGateway;
    private final TelegramIntakeService telegramIntakeService;
    private final ScenarioRouter scenarioRouter;
    private final UserEventProducer userEventProducer;
    private final LlmScenarioPromptCatalog llmScenarioPromptCatalog;
    private final VoiceTranscriptionRetryService voiceTranscriptionRetryService;
    private final FsmTimelineWriter fsmTimelineWriter;
    private final TelegramSystemNotifier telegramSystemNotifier;
    private final ModelInteractionAuditRepository modelInteractionAuditRepository;
    private final OpsTelegramCommandService opsTelegramCommandService;
    private final OpsGroupQuestionAnswerService opsGroupQuestionAnswerService;
    private final OpsGroupMessageIntakeService opsGroupMessageIntakeService;

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;

    @Value("${telegram.analytics.chat-id:}")
    private String analyticsChatId;

    @Value("${telegram.system.chat-id:}")
    private String systemChatId;

    @Value("${telegram.ops.chat-id:}")
    private String opsChatId;

    @Value("${astor.message.log-conversations-enabled:true}")
    private boolean logConversationsEnabled;

    /** Web-only cost guard for the model path; optional so Telegram-only wiring and tests stay unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.web.WebModelBudget webModelBudget;

    public OutgoingMessage handle(IncomingMessage incoming) {
        if (incoming == null || incoming.chatId() == null) {
            throw new IllegalArgumentException("Incoming message must contain chatId for current MVP flow");
        }

        String text = normalize(incoming.text());
        BotState currentState = resolveState(incoming.chatId());
        telegramIntakeService.capture(incoming);

        if (logConversationsEnabled) {
            log.info(
                    "Message gateway received channel={}, chatId={}, state={}, text={}",
                    incoming.channel(),
                    incoming.chatId(),
                    currentState,
                    text
            );
        } else {
            log.debug(
                    "Message gateway received channel={}, chatId={}, state={}",
                    incoming.channel(),
                    incoming.chatId(),
                    currentState
            );
        }

        if (isOpsCommandFromGroup(incoming, text)) {
            OutgoingMessage opsCommand = opsTelegramCommandService.handle(incoming, currentState, text)
                    .orElse(null);
            if (opsCommand != null) {
                return finish(incoming, currentState, opsCommand);
            }
        }

        if (isTelegramGroupChat(incoming.chatId())) {
            java.util.Optional<OutgoingMessage> groupAnswer = opsGroupQuestionAnswerService.handle(incoming, currentState, text);
            if (groupAnswer != null && groupAnswer.isPresent()) {
                return finish(incoming, currentState, groupAnswer.get());
            }
            java.util.Optional<OutgoingMessage> groupIntake = opsGroupMessageIntakeService.handle(incoming, currentState, text);
            if (groupIntake != null && groupIntake.isPresent()) {
                return finish(incoming, currentState, groupIntake.get());
            }
        }

        if (isTelegramGroupChat(incoming.chatId()) && isOpsServiceChat(incoming.chatId())) {
            return finish(incoming, currentState, OutgoingMessage.of(
                    incoming,
                    "",
                    currentState.name(),
                    false,
                    false,
                    false,
                    false,
                    AdminAlert.none(),
                    List.of("GROUP_CHAT_CHECK", "SKIP_GUEST_FSM")
            ));
        }

        if (isServiceChat(incoming.chatId())) {
            OutgoingMessage opsCommand = opsTelegramCommandService.handle(incoming, currentState, text)
                    .orElse(null);
            if (opsCommand != null) {
                return finish(incoming, currentState, opsCommand);
            }
            return finish(incoming, currentState, OutgoingMessage.of(
                    incoming,
                    """
                    Service chat online.
                    Я вижу этот чат как служебный канал Astor Butler: сообщения сохраняю, Kafka-события публикую, в гостевой FSM-сценарий этот чат не отправляю.

                    Инструкции: https://auspicious-kryptops-863.notion.site/Astor-Butler-380a7c019f1980d78b68d8bc659c609b?source=copy_link
                    """.strip(),
                    currentState.name(),
                    false,
                    false,
                    false,
                    false,
                    AdminAlert.none(),
                    List.of("SERVICE_CHAT_CHECK", "SKIP_GUEST_FSM")
            ));
        }

        if (isTelegramGroupChat(incoming.chatId())) {
            return finish(incoming, currentState, OutgoingMessage.of(
                    incoming,
                    "",
                    currentState.name(),
                    false,
                    false,
                    false,
                    false,
                    AdminAlert.none(),
                    List.of("GROUP_CHAT_CHECK", "SKIP_GUEST_FSM")
            ));
        }

        if (!isVoiceMessage(incoming) || !text.isBlank()) {
            voiceTranscriptionRetryService.reset(incoming.chatId());
        }

        OutgoingMessage routed = scenarioRouter.route(incoming, currentState, text);
        if (routed != null) {
            return finish(incoming, currentState, routed);
        }

        if (isVoiceMessage(incoming) && text.isBlank()) {
            long attempts = voiceTranscriptionRetryService.recordFailure(incoming.chatId());
            if (attempts >= 2) {
                return finish(incoming, currentState, OutgoingMessage.of(
                        incoming,
                        voiceTextFallbackText(incoming),
                        currentState.name(),
                        false,
                        false,
                        true,
                        false,
                        AdminAlert.none(),
                        List.of("VOICE_RECEIVED", "TRANSCRIPTION_FAILED_TWICE", "ASK_TEXT_INPUT")
                ));
            }
            return finish(incoming, currentState, OutgoingMessage.of(
                    incoming,
                    voiceRetryText(incoming),
                    currentState.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("VOICE_RECEIVED", "TRANSCRIPTION_RETRY_REQUESTED")
            ));
        }

        if (text.isBlank()) {
            return finish(incoming, currentState, OutgoingMessage.of(
                    incoming,
                    "",
                    currentState.name(),
                    false,
                    false,
                    false,
                    false,
                    AdminAlert.none(),
                    List.of("EMPTY_MESSAGE_IGNORED", "SKIP_GUEST_FSM")
            ));
        }

        return aiAssistedReply(incoming, currentState, text);
    }

    private OutgoingMessage aiAssistedReply(IncomingMessage incoming, BotState currentState, String text) {
        if (webModelBudget != null) {
            museon_online.astor_butler.domain.web.WebModelBudget.Decision budget = webModelBudget.check(incoming, text);
            if (!budget.allowed()) {
                return finish(incoming, currentState, OutgoingMessage.of(
                        incoming,
                        budget.replyText(),
                        currentState.name(),
                        false,
                        false,
                        false,
                        false,
                        AdminAlert.none(),
                        List.of("WEB_MODEL_GUARD", "WEB_MODEL_" + budget.reason().toUpperCase())
                ));
            }
        }
        String prompt = """
                Ты AI-адаптер Astor Butler. Telegram является только UI, бизнес-логика живет в FSM.
                Ответь пользователю коротко и вежливо, строго по FSM-контракту ниже.
                Не подтверждай бронь сам: подтверждение делает только доменный слой после кнопки хостес.
                Если запрос неясен, честно попроси одно уточнение или скажи, что уточнишь у команды.

                %s

                Текущее FSM-состояние: %s
                Сообщение пользователя: "%s"
                """.formatted(llmScenarioPromptCatalog.tableBookingContract(), currentState, text);

        try {
            ModelTextResponse modelResponse = modelGateway.generateText(ModelTextRequest.of(
                    prompt,
                    "MessageGateway",
                    currentState.name(),
                    "fallback-reply"
            ));
            String aiText = modelResponse.text();
            if (aiText == null || aiText.isBlank()) {
                auditModelInteraction(incoming, currentState, prompt, "", modelResponse, false, true, true, null);
                return fallback(incoming, currentState, "LLM returned blank response");
            }
            auditModelInteraction(incoming, currentState, prompt, aiText, modelResponse, true, modelResponse.fallback(), true, null);
            userEventProducer.publishLlmResponse(
                    incoming,
                    currentState,
                    "AI_RESPONSE",
                    prompt,
                    aiText,
                    false
            );

            fsmStorage.setState(incoming.chatId(), BotState.AI_FALLBACK);
            return finish(incoming, currentState, OutgoingMessage.of(
                    incoming,
                    aiText,
                    BotState.AI_FALLBACK.name(),
                    false,
                    false,
                    true,
                    false,
                    AdminAlert.none(),
                    List.of("AI_RESPONSE")
            ));
        } catch (Exception e) {
            auditModelInteraction(incoming, currentState, prompt, "", null, false, true, false, e);
            log.warn(
                    "LLM fallback used for chatId={}, state={}, reason={}: {}",
                    incoming.chatId(),
                    currentState,
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
            return fallback(incoming, currentState, e.getClass().getSimpleName());
        }
    }

    private void auditModelInteraction(
            IncomingMessage incoming,
            BotState currentState,
            String prompt,
            String responseText,
            ModelTextResponse modelResponse,
            boolean generated,
            boolean fallbackUsed,
            boolean success,
            Exception error
    ) {
        modelInteractionAuditRepository.capture(new ModelInteractionAuditRecord(
                "AERIS",
                incoming.channel() == null ? "" : incoming.channel().name(),
                incoming.chatId(),
                incoming.telegramUserId(),
                incoming.correlationId(),
                "MessageGateway",
                currentState == null ? "" : currentState.name(),
                "fallback-reply",
                modelResponse == null ? "" : modelResponse.provider(),
                modelResponse == null ? "" : modelResponse.model(),
                modelResponse == null || modelResponse.metadata() == null ? "" : String.valueOf(modelResponse.metadata().getOrDefault("profile", "")),
                prompt,
                text(incoming.text()),
                "",
                responseText,
                generated,
                fallbackUsed,
                success,
                error == null ? "" : error.getClass().getSimpleName(),
                error == null ? "" : error.getMessage(),
                modelResponse == null ? null : modelResponse.latency(),
                java.util.Map.of("source", "MessageGatewayService")
        ));
    }

    private OutgoingMessage fallback(IncomingMessage incoming, BotState currentState, String reason) {
        fsmStorage.setState(incoming.chatId(), BotState.AI_FALLBACK);
        String userText = "Я не смог уверенно разобрать запрос. Я передам это администратору, а вы можете написать проще: бронирование, меню, афиша или менеджер.";

        return finish(incoming, currentState, OutgoingMessage.of(
                incoming,
                userText,
                BotState.AI_FALLBACK.name(),
                false,
                false,
                true,
                true,
                adminAlert(incoming, currentState, reason),
                List.of("FALLBACK", "ADMIN_ALERT")
        ));
    }

    private OutgoingMessage finish(IncomingMessage incoming, BotState previousState, OutgoingMessage outgoing) {
        boolean kafkaOutboxQueued = userEventProducer.publishIncomingMessage(incoming, previousState, outgoing);
        fsmTimelineWriter.append(FsmTimelineEvent.from(incoming, previousState, outgoing));
        telegramSystemNotifier.sendTransition(incoming, previousState, outgoing, kafkaOutboxQueued);
        return outgoing;
    }

    private AdminAlert adminAlert(IncomingMessage incoming, BotState currentState, String reason) {
        if (adminChatId == null || adminChatId.isBlank()) {
            return AdminAlert.none();
        }

        String text = """
                <b>Astor Butler / fallback</b>
                Требуется внимание администратора

                <b>%s</b>
                chat %s / user %s%s

                <b>Сообщение гостя</b>
                <blockquote>%s</blockquote>

                <b>Что случилось</b>
                State: %s -> %s
                Reason: %s

                <b>Действие</b>
                Проверь диалог и ответь гостю вручную, если AI/FSM не восстановится.

                <b>Техника</b>
                Channel: %s
                Correlation: %s
                """.formatted(
                html(displayName(incoming)),
                html(text(incoming.chatId())),
                html(text(incoming.telegramUserId())),
                incoming.username() == null || incoming.username().isBlank() ? "" : " / @" + html(incoming.username()),
                html(blankAsEmptyLabel(incoming.text())),
                html(text(currentState)),
                html(BotState.AI_FALLBACK.name()),
                html(blankAsEmptyLabel(reason)),
                html(text(incoming.channel())),
                html(blankAsEmptyLabel(incoming.correlationId()))
        );
        return new AdminAlert(true, adminChatId, text);
    }

    private String displayName(IncomingMessage incoming) {
        String firstName = normalize(incoming.firstName());
        String lastName = normalize(incoming.lastName());
        String username = normalize(incoming.username());
        String fullName = (firstName + " " + lastName).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (!username.isBlank()) {
            return "@" + username;
        }
        return "unknown";
    }

    private BotState resolveState(Long chatId) {
        BotState current = fsmStorage.getState(chatId);
        if (current != null) {
            return current;
        }
        fsmStorage.setState(chatId, BotState.UNKNOWN);
        return BotState.UNKNOWN;
    }

    private boolean isVoiceMessage(IncomingMessage incoming) {
        if (incoming == null || incoming.payload() == null) {
            return false;
        }
        Object mediaKind = incoming.payload().get("mediaKind");
        return "VOICE".equals(mediaKind) || "AUDIO".equals(mediaKind);
    }

    private String voiceRetryText(IncomingMessage incoming) {
        Object reason = incoming.payload() == null ? null : incoming.payload().get("transcriptionReason");
        if (reason == null || reason.toString().isBlank() || "STT disabled".equals(reason)) {
            return "Голосовое получил. Сейчас расшифровка голоса выключена, поэтому напишите запрос текстом — я сразу продолжу тот же сценарий.";
        }
        if ("VOICE_DURATION_LIMIT_EXCEEDED".equals(reason.toString())) {
            return "Голосовое получилось слишком длинным для безопасной расшифровки. Напишите, пожалуйста, коротко текстом: меню, бронь, видео-тур или помощь команды.";
        }
        if ("VOICE_FILE_SIZE_LIMIT_EXCEEDED".equals(reason.toString())) {
            return "Файл голосового слишком большой для текущего лимита. Напишите запрос текстом или отправьте короткое голосовое.";
        }
        return "Голосовое получил, но расшифровка не сложилась. Попробуйте, пожалуйста, еще раз: чуть медленнее и без фонового шума.";
    }

    private String voiceTextFallbackText(IncomingMessage incoming) {
        return "Я дважды не смог надежно разобрать голосовое. Чтобы не потерять ваш запрос, напишите, пожалуйста, коротко текстом — я сразу продолжу сценарий.";
    }

    private boolean isServiceChat(Long chatId) {
        if (chatId == null) {
            return false;
        }
        String value = chatId.toString();
        return value.equals(adminChatId) || value.equals(analyticsChatId) || value.equals(systemChatId) || value.equals(opsChatId);
    }

    private boolean isOpsServiceChat(Long chatId) {
        return chatId != null && opsChatId != null && !opsChatId.isBlank() && chatId.toString().equals(opsChatId);
    }

    private boolean isOpsCommandFromGroup(IncomingMessage incoming, String text) {
        return incoming != null
                && isTelegramGroupChat(incoming.chatId())
                && opsTelegramCommandService.supports(text);
    }

    private boolean isTelegramGroupChat(Long chatId) {
        return chatId != null && chatId < 0;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String blankAsEmptyLabel(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "(empty)" : normalized;
    }

    private String html(String value) {
        return text(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
