package museon_online.astor_butler.service.message;

import museon_online.astor_butler.domain.semantic.IntentExampleRepository;
import museon_online.astor_butler.domain.telegram.TelegramIntakeService;
import museon_online.astor_butler.domain.timeline.FsmTimelineEvent;
import museon_online.astor_butler.domain.timeline.FsmTimelineWriter;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.scenario.ArtAuctionScenario;
import museon_online.astor_butler.fsm.scenario.ChangeCancelScenario;
import museon_online.astor_butler.fsm.scenario.ConciergeScenario;
import museon_online.astor_butler.fsm.scenario.EventBookingScenario;
import museon_online.astor_butler.fsm.scenario.FeedbackScenario;
import museon_online.astor_butler.fsm.scenario.BusinessLunchScenario;
import museon_online.astor_butler.fsm.scenario.FirstTouchScenario;
import museon_online.astor_butler.fsm.scenario.FsmScenario;
import museon_online.astor_butler.fsm.scenario.HiddenHeartScenario;
import museon_online.astor_butler.fsm.scenario.ImpactMeterScenario;
import museon_online.astor_butler.fsm.scenario.MainMenuScenario;
import museon_online.astor_butler.fsm.scenario.ManagerHelpScenario;
import museon_online.astor_butler.fsm.scenario.MerchScenario;
import museon_online.astor_butler.fsm.scenario.MenuAssetsScenario;
import museon_online.astor_butler.fsm.scenario.PreferenceScenario;
import museon_online.astor_butler.fsm.scenario.QuietGuideScenario;
import museon_online.astor_butler.fsm.scenario.RecoveryScenario;
import museon_online.astor_butler.fsm.scenario.SafePlayScenario;
import museon_online.astor_butler.fsm.scenario.ScenarioRouter;
import museon_online.astor_butler.fsm.scenario.SmartTipScenario;
import museon_online.astor_butler.fsm.scenario.TableBookingScenario;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.kafka.UserEventProducer;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelInteractionAuditRecord;
import museon_online.astor_butler.model.ModelInteractionAuditRepository;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageGatewayServiceTest {

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private ModelGateway modelGateway;

    @Mock
    private TelegramIntakeService telegramIntakeService;

    @Mock
    private FirstTouchScenario firstTouchScenario;

    @Mock
    private BusinessLunchScenario businessLunchScenario;

    @Mock
    private MainMenuScenario mainMenuScenario;

    @Mock
    private ManagerHelpScenario managerHelpScenario;

    @Mock
    private FeedbackScenario feedbackScenario;

    @Mock
    private PreferenceScenario preferenceScenario;

    @Mock
    private ConciergeScenario conciergeScenario;

    @Mock
    private SafePlayScenario safePlayScenario;

    @Mock
    private MerchScenario merchScenario;

    @Mock
    private ChangeCancelScenario changeCancelScenario;

    @Mock
    private EventBookingScenario eventBookingScenario;

    @Mock
    private MenuAssetsScenario menuAssetsScenario;

    @Mock
    private QuietGuideScenario quietGuideScenario;

    @Mock
    private ImpactMeterScenario impactMeterScenario;

    @Mock
    private RecoveryScenario recoveryScenario;

    @Mock
    private SmartTipScenario smartTipScenario;

    @Mock
    private HiddenHeartScenario hiddenHeartScenario;

    @Mock
    private ArtAuctionScenario artAuctionScenario;

    @Mock
    private TableBookingScenario tableBookingScenario;

    @Mock
    private UserEventProducer userEventProducer;

    @Mock
    private LlmScenarioPromptCatalog llmScenarioPromptCatalog;

    @Mock
    private VoiceTranscriptionRetryService voiceTranscriptionRetryService;

    @Mock
    private FsmTimelineWriter fsmTimelineWriter;

    @Mock
    private TelegramSystemNotifier telegramSystemNotifier;

    @Mock
    private IntentExampleRepository intentExampleRepository;

    @Mock
    private ModelInteractionAuditRepository modelInteractionAuditRepository;

    @Mock
    private OpsTelegramCommandService opsTelegramCommandService;

    @Mock
    private OpsGroupQuestionAnswerService opsGroupQuestionAnswerService;

    @Mock
    private OpsGroupMessageIntakeService opsGroupMessageIntakeService;

    private MessageGatewayService service;

    @BeforeEach
    void setUp() {
        bridgeUnderstandingAwareCalls(
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
        ScenarioRouter scenarioRouter = new ScenarioRouter(
                fsmStorage,
                firstTouchScenario,
                businessLunchScenario,
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
                recoveryScenario,
                new GuestInputUnderstandingService(),
                intentExampleRepository
        );
        service = new MessageGatewayService(
                fsmStorage,
                modelGateway,
                telegramIntakeService,
                scenarioRouter,
                userEventProducer,
                llmScenarioPromptCatalog,
                voiceTranscriptionRetryService,
                fsmTimelineWriter,
                telegramSystemNotifier,
                modelInteractionAuditRepository,
                opsTelegramCommandService,
                opsGroupQuestionAnswerService,
                opsGroupMessageIntakeService
        );
        ReflectionTestUtils.setField(service, "adminChatId", "100500");
        ReflectionTestUtils.setField(service, "analyticsChatId", "100501");
        ReflectionTestUtils.setField(service, "systemChatId", "-5403153261");
        ReflectionTestUtils.setField(service, "opsChatId", "-100900");
        ReflectionTestUtils.setField(service, "logConversationsEnabled", true);
        lenient().when(opsGroupQuestionAnswerService.handle(any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(opsGroupMessageIntakeService.handle(any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
    }

    private void bridgeUnderstandingAwareCalls(FsmScenario... scenarios) {
        for (FsmScenario scenario : scenarios) {
            lenient().when(scenario.supports(any(), any(), any(), any()))
                    .thenAnswer(invocation -> scenario.supports(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2)
                    ));
            lenient().when(scenario.handle(any(), any(), any(), any()))
                    .thenAnswer(invocation -> scenario.handle(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2)
                    ));
        }
    }

    @Test
    void returnsFallbackWhenLlmTimesOut() {
        IncomingMessage incoming = telegram("Check админки");
        String routeText = "check админки";
        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(llmScenarioPromptCatalog.tableBookingContract()).thenReturn("table booking contract");
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(routeText)))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(routeText)))
                .thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(routeText)))
                .thenReturn(false);
        when(mainMenuScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(routeText)))
                .thenReturn(false);
        when(modelGateway.generateText(any())).thenThrow(new ResourceAccessException("Read timed out"));

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.fallback()).isTrue();
        assertThat(outgoing.nextState()).isEqualTo(BotState.AI_FALLBACK.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.adminAlert().chatId()).isEqualTo("100500");
        assertThat(outgoing.adminAlert().text()).contains("Astor Butler / fallback");
        assertThat(outgoing.actions()).containsExactly("FALLBACK", "ADMIN_ALERT");
        verify(fsmStorage).setState(incoming.chatId(), BotState.AI_FALLBACK);
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
        verify(fsmTimelineWriter).append(any(FsmTimelineEvent.class));
        verify(modelInteractionAuditRepository).capture(any(ModelInteractionAuditRecord.class));
    }

    @Test
    void handlesOpsCommandInsideOpsServiceChatBeforeGuestFsm() {
        IncomingMessage incoming = IncomingMessage.telegram(
                -100900L,
                1773317437L,
                1,
                100,
                "/projects",
                null,
                "Michael",
                null,
                "michaelwelly",
                "ru",
                false,
                "100"
        );
        OutgoingMessage opsResponse = OutgoingMessage.of(
                incoming,
                "<b>Smart Solution Ops / active projects</b>",
                BotState.READY_FOR_DIALOG.name(),
                true,
                false,
                false,
                false,
                AdminAlert.none(),
                java.util.List.of("OPS_TELEGRAM_COMMAND", "SKIP_GUEST_FSM")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(opsTelegramCommandService.supports("/projects")).thenReturn(true);
        when(opsTelegramCommandService.handle(incoming, BotState.READY_FOR_DIALOG, "/projects"))
                .thenReturn(java.util.Optional.of(opsResponse));

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.text()).contains("Smart Solution Ops");
        assertThat(outgoing.html()).isTrue();
        verify(opsTelegramCommandService).handle(incoming, BotState.READY_FOR_DIALOG, "/projects");
        verify(firstTouchScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void handlesOpsCommandFromAnyTelegramGroupBeforeGuestConsent() {
        IncomingMessage incoming = IncomingMessage.telegram(
                -1003975140329L,
                1773317437L,
                1,
                100,
                "/ops@astor_butler_bot",
                null,
                "Michael",
                null,
                "michaelwelly",
                "ru",
                false,
                "100"
        );
        OutgoingMessage opsResponse = OutgoingMessage.of(
                incoming,
                "<b>Smart Solution Ops</b>",
                BotState.UNKNOWN.name(),
                true,
                false,
                false,
                false,
                AdminAlert.none(),
                java.util.List.of("OPS_TELEGRAM_COMMAND", "SKIP_GUEST_FSM")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.UNKNOWN);
        when(opsTelegramCommandService.supports("/ops@astor_butler_bot")).thenReturn(true);
        when(opsTelegramCommandService.handle(incoming, BotState.UNKNOWN, "/ops@astor_butler_bot"))
                .thenReturn(java.util.Optional.of(opsResponse));

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.text()).contains("Smart Solution Ops");
        assertThat(outgoing.requestContact()).isFalse();
        assertThat(outgoing.actions()).containsExactly("OPS_TELEGRAM_COMMAND", "SKIP_GUEST_FSM");
        verify(opsTelegramCommandService).supports("/ops@astor_butler_bot");
        verify(firstTouchScenario, never()).supports(any(), any(), any());
        verify(fsmStorage, never()).setState(incoming.chatId(), BotState.CONSENT_REQUIRED);
        verify(modelGateway, never()).generateText(any());
    }

    @Test
    void silentlySkipsNonOpsTelegramGroupMessagesBeforeGuestConsent() {
        IncomingMessage incoming = IncomingMessage.telegram(
                -1003975140329L,
                1773317437L,
                1,
                100,
                "обычный текст в группе",
                null,
                "Michael",
                null,
                "michaelwelly",
                "ru",
                false,
                "100"
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.CONSENT_REQUIRED);
        when(opsTelegramCommandService.supports("обычный текст в группе")).thenReturn(false);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.text()).isBlank();
        assertThat(outgoing.requestContact()).isFalse();
        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        assertThat(outgoing.actions()).containsExactly("GROUP_CHAT_CHECK", "SKIP_GUEST_FSM");
        verify(firstTouchScenario, never()).supports(any(), any(), any());
        verify(fsmStorage, never()).setState(incoming.chatId(), BotState.CONSENT_REQUIRED);
        verify(modelGateway, never()).generateText(any());
    }

    @Test
    void handlesGroupQuestionAnswerBeforeGuestFsm() {
        IncomingMessage incoming = IncomingMessage.telegram(
                -1003975140329L,
                1773317437L,
                1,
                100,
                "что по презентации MED?",
                null,
                "Michael",
                null,
                "michaelwelly",
                "ru",
                false,
                "100"
        );
        OutgoingMessage qaResponse = OutgoingMessage.of(
                incoming,
                "<b>Smart Solution Ops</b>\nMED у Майкла.",
                BotState.CONSENT_REQUIRED.name(),
                true,
                false,
                false,
                false,
                AdminAlert.none(),
                java.util.List.of("GROUP_QA_RAG_ANSWER", "SKIP_GUEST_FSM")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.CONSENT_REQUIRED);
        when(opsTelegramCommandService.supports("что по презентации MED?")).thenReturn(false);
        when(opsGroupQuestionAnswerService.handle(incoming, BotState.CONSENT_REQUIRED, "что по презентации MED?"))
                .thenReturn(java.util.Optional.of(qaResponse));

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.text()).contains("MED");
        assertThat(outgoing.actions()).containsExactly("GROUP_QA_RAG_ANSWER", "SKIP_GUEST_FSM");
        verify(firstTouchScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.CONSENT_REQUIRED, outgoing);
    }

    @Test
    void handlesOpsGroupIntakeBeforeGuestFsm() {
        IncomingMessage incoming = IncomingMessage.telegram(
                -1003975140329L,
                1773317437L,
                1,
                100,
                "MED статус 70% презентация у @michael на ревью",
                null,
                "Michael",
                null,
                "michaelwelly",
                "ru",
                false,
                "100"
        );
        OutgoingMessage intakeResponse = OutgoingMessage.of(
                incoming,
                "<b>Smart Solution Ops / intake</b>",
                BotState.CONSENT_REQUIRED.name(),
                true,
                false,
                false,
                false,
                AdminAlert.none(),
                java.util.List.of("OPS_GROUP_MESSAGE_INTAKE", "OPS_PROJECT_STATUS_UPDATED", "PROJECT_MEMORY_UPDATED", "SKIP_GUEST_FSM")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.CONSENT_REQUIRED);
        when(opsTelegramCommandService.supports(incoming.text())).thenReturn(false);
        when(opsGroupQuestionAnswerService.handle(incoming, BotState.CONSENT_REQUIRED, incoming.text()))
                .thenReturn(java.util.Optional.empty());
        when(opsGroupMessageIntakeService.handle(incoming, BotState.CONSENT_REQUIRED, incoming.text()))
                .thenReturn(java.util.Optional.of(intakeResponse));

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.text()).contains("Smart Solution Ops / intake");
        assertThat(outgoing.actions()).containsExactly(
                "OPS_GROUP_MESSAGE_INTAKE",
                "OPS_PROJECT_STATUS_UPDATED",
                "PROJECT_MEMORY_UPDATED",
                "SKIP_GUEST_FSM"
        );
        verify(firstTouchScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.CONSENT_REQUIRED, outgoing);
    }

    @Test
    void routesTableBookingIntentBeforeLlm() {
        IncomingMessage incoming = telegram("Хочу забронировать столик завтра на 20:00 на двоих");
        String routeText = "хочу забронировать столик завтра на 20:00 на 2 гостей";
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Отправляю план зала AERIS.",
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                java.util.List.of("TABLE_BOOKING_SHOW_PLAN", "SEND_HALL_PLAN")
        ).withMetadata(java.util.Map.of("documentObjectKey", "content/aeris/floor-plan/AERIS_PLAN.pdf"));

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(routeText)))
                .thenReturn(true);
        when(tableBookingScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(routeText)))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name());
        assertThat(outgoing.metadata()).containsEntry("documentObjectKey", "content/aeris/floor-plan/AERIS_PLAN.pdf");
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
        verify(fsmTimelineWriter).append(any(FsmTimelineEvent.class));
    }

    @Test
    void routesMainMenuBeforeLlmWhenNoExplicitScenarioMatches() {
        IncomingMessage incoming = telegram("главное меню");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Я на связи. Что сделаем?",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("MAIN_MENU_READY")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(impactMeterScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(smartTipScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(hiddenHeartScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(artAuctionScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(mainMenuScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(mainMenuScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).containsExactly("MAIN_MENU_READY");
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesImpactMeterBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("покажи impact итоги");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Impact Meter покажет агрегированные итоги.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("IMPACT_METER", "SHOW_IMPACT_SUMMARY")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(changeCancelScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(impactMeterScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(impactMeterScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).containsExactly("IMPACT_METER", "SHOW_IMPACT_SUMMARY");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesSmartTipBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("хочу оставить чаевые 1000");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Принял сумму чаевых.",
                BotState.TIP_CONFIRMATION.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("SMART_TIP", "TIP_CONFIRMATION")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(changeCancelScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(smartTipScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(smartTipScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.TIP_CONFIRMATION.name());
        assertThat(outgoing.actions()).containsExactly("SMART_TIP", "TIP_CONFIRMATION");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesHiddenHeartBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("хочу сделать донат 1000");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Собрал donation draft.",
                BotState.DONATION_CONFIRMATION.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("HIDDEN_HEART", "DONATION_CONFIRMATION", "IMPACT_EVENT_DRAFT")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(changeCancelScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(smartTipScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(hiddenHeartScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(hiddenHeartScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.DONATION_CONFIRMATION.name());
        assertThat(outgoing.actions()).containsExactly("HIDDEN_HEART", "DONATION_CONFIRMATION", "IMPACT_EVENT_DRAFT");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesArtAuctionBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("ставлю 20000 за картину");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Ставку вижу, проверяю активный лот.",
                BotState.AUCTION_WAIT_BID.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("ART_AUCTION", "VALIDATE_AUCTION_BID", "ASK_EXPLICIT_CONFIRMATION")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(changeCancelScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(smartTipScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(hiddenHeartScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(artAuctionScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(artAuctionScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.AUCTION_WAIT_BID.name());
        assertThat(outgoing.actions()).containsExactly("ART_AUCTION", "VALIDATE_AUCTION_BID", "ASK_EXPLICIT_CONFIRMATION");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesManagerHelpBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("позови менеджера, хочу обсудить банкет");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Передал запрос команде.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                new AdminAlert(true, "100500", "manager help card"),
                java.util.List.of("MANAGER_HELP", "MANAGER_HELP_DIRECT_REQUEST", "ADMIN_ALERT", "RETURN_MAIN_MENU")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(managerHelpScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.actions()).containsExactly("MANAGER_HELP", "MANAGER_HELP_DIRECT_REQUEST", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesFeedbackBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("отзыв: интерьер красивый, но музыку хотелось тише");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Спасибо, передал отзыв команде.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                new AdminAlert(true, "100500", "feedback card"),
                java.util.List.of("FEEDBACK", "FEEDBACK_DIRECT_TEXT", "ADMIN_ALERT", "RETURN_MAIN_MENU")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(changeCancelScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(feedbackScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(feedbackScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.actions()).containsExactly("FEEDBACK", "FEEDBACK_DIRECT_TEXT", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesMerchBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("хочу купить сабражную цепь");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Передал запрос по мерчу команде.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                new AdminAlert(true, "100500", "merch card"),
                java.util.List.of("MERCH", "MERCH_DIRECT_REQUEST", "ADMIN_ALERT", "RETURN_MAIN_MENU")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(changeCancelScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(feedbackScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(merchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(merchScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.actions()).containsExactly("MERCH", "MERCH_DIRECT_REQUEST", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesSafePlayBeforeMerchMainMenuAndLlm() {
        IncomingMessage incoming = telegram("хотим сабраж с шампанским к столу в 21:00");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Передал запрос команде AERIS.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                new AdminAlert(true, "100500", "safe play card"),
                java.util.List.of(
                        "SAFE_PLAY",
                        "SAFE_PLAY_DIRECT_REQUEST",
                        "TEAM_CONFIRMATION_REQUIRED",
                        "NO_DANGEROUS_HOW_TO",
                        "ADMIN_ALERT",
                        "RETURN_MAIN_MENU"
                )
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(changeCancelScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(feedbackScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(safePlayScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(safePlayScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.actions()).containsExactly(
                "SAFE_PLAY",
                "SAFE_PLAY_DIRECT_REQUEST",
                "TEAM_CONFIRMATION_REQUIRED",
                "NO_DANGEROUS_HOW_TO",
                "ADMIN_ALERT",
                "RETURN_MAIN_MENU"
        );
        verify(merchScenario, never()).supports(any(), any(), any());
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesEventBookingBeforeMainMenuAndLlm() {
        IncomingMessage incoming = telegram("день рождения 20 июня в 19:00 на 25 гостей, нужен банкет");
        OutgoingMessage scenarioResponse = OutgoingMessage.of(
                incoming,
                "Собрал заявку на мероприятие.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                new AdminAlert(true, "100500", "event booking card"),
                java.util.List.of("EVENT_BOOKING", "EVENT_REQUEST_SENT", "ADMIN_ALERT", "RETURN_MAIN_MENU")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(eventBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(eventBookingScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(scenarioResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.actions()).containsExactly("EVENT_BOOKING", "EVENT_REQUEST_SENT", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void routesUnclearTextToRecoveryBeforeLlm() {
        IncomingMessage incoming = telegram("какая-то странная фраза без сценария");
        OutgoingMessage recoveryResponse = OutgoingMessage.of(
                incoming,
                "Я не хочу угадать неправильно.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("RECOVERY", "CLARIFY_INTENT", "SHOW_MAIN_OPTIONS")
        );

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(managerHelpScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(mainMenuScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(recoveryScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(recoveryScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(recoveryResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).containsExactly("RECOVERY", "CLARIFY_INTENT", "SHOW_MAIN_OPTIONS");
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void skipsGuestFsmForSystemChat() {
        IncomingMessage incoming = IncomingMessage.telegram(
                -5403153261L,
                1L,
                7,
                284070039,
                "",
                null,
                "System",
                "Chat",
                null,
                "ru",
                false,
                "284070039"
        );
        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.CONSENT_REQUIRED);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        assertThat(outgoing.actions()).containsExactly("SERVICE_CHAT_CHECK", "SKIP_GUEST_FSM");
        assertThat(outgoing.text()).contains("Service chat online", "гостевой FSM-сценарий");
        verify(firstTouchScenario, never()).supports(any(), any(), any());
        verify(tableBookingScenario, never()).supports(any(), any(), any());
        verify(managerHelpScenario, never()).supports(any(), any(), any());
        verify(feedbackScenario, never()).supports(any(), any(), any());
        verify(safePlayScenario, never()).supports(any(), any(), any());
        verify(merchScenario, never()).supports(any(), any(), any());
        verify(menuAssetsScenario, never()).supports(any(), any(), any());
        verify(quietGuideScenario, never()).supports(any(), any(), any());
        verify(impactMeterScenario, never()).supports(any(), any(), any());
        verify(smartTipScenario, never()).supports(any(), any(), any());
        verify(hiddenHeartScenario, never()).supports(any(), any(), any());
        verify(artAuctionScenario, never()).supports(any(), any(), any());
        verify(mainMenuScenario, never()).supports(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.CONSENT_REQUIRED, outgoing);
        verify(fsmTimelineWriter).append(any(FsmTimelineEvent.class));
    }

    @Test
    void executesSafeContentCompositeIntentTogether() {
        IncomingMessage incoming = telegram("покажи винную карту и видео-тур");
        OutgoingMessage menuResponse = OutgoingMessage.of(
                incoming,
                "Отправляю винную карту.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("MENU_ASSETS", "MENU_ASSETS_DELIVERED", "RETURN_MAIN_MENU")
        ).withMetadata(java.util.Map.of(
                "scenario", "MenuAssetsScenario",
                "documents", java.util.List.of(java.util.Map.of(
                        "objectKey", "content/aeris-menu/wine.pdf",
                        "filename", "WINE MENU.pdf",
                        "caption", "Винная карта"
                ))
        ));
        OutgoingMessage guideResponse = OutgoingMessage.of(
                incoming,
                "Отправляю видео-тур.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("QUIET_GUIDE", "INTERIOR_VIDEO", "QUIET_GUIDE_DELIVERED", "RETURN_MAIN_MENU")
        ).withMetadata(java.util.Map.of(
                "scenario", "QuietGuideScenario",
                "videoObjectKey", "content/aeris/interior.mp4",
                "videoFilename", "INTERIOR.mp4",
                "videoCaption", "AERIS interior tour",
                "videoSendMode", "DOCUMENT"
        ));

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(true);
        when(menuAssetsScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(menuResponse);
        when(quietGuideScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(guideResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.text()).contains("Отправляю винную карту.", "Отправляю видео-тур.");
        assertThat(outgoing.actions()).contains("COMPOSITE_INTENT_PLAN", "MENU_ASSETS_DELIVERED", "INTERIOR_VIDEO");
        assertThat(outgoing.metadata()).containsEntry("scenario", "CompositeIntentPlan");
        assertThat(outgoing.metadata()).containsEntry("compositePlan", "PARALLEL_CONTENT");
        assertThat(outgoing.metadata()).containsEntry("videoObjectKey", "content/aeris/interior.mp4");
        assertThat(outgoing.metadata().get("documents")).asList().hasSize(1);
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void defersSecondaryContentWhenCompositeIntentContainsTableBooking() {
        IncomingMessage incoming = telegram("забронируй стол завтра в 20:00 на двоих и пришли винную карту");
        OutgoingMessage bookingResponse = OutgoingMessage.of(
                incoming,
                "Отправляю план зала AERIS.",
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                java.util.List.of("SEND_HALL_PLAN", "ASK_TABLE_SELECTION")
        ).withMetadata(java.util.Map.of("documentObjectKey", "content/aeris/floor-plan/AERIS_PLAN.pdf"));

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(incoming.text())))
                .thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), anyString()))
                .thenReturn(true);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), anyString()))
                .thenReturn(true);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), anyString()))
                .thenReturn(false);
        when(tableBookingScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), anyString()))
                .thenReturn(bookingResponse);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name());
        assertThat(outgoing.text()).contains("Отправляю план зала", "После шага с бронью");
        assertThat(outgoing.metadata()).containsEntry("scenario", "CompositeIntentPlan");
        assertThat(outgoing.metadata()).containsEntry("compositePlan", "SEQUENTIAL");
        assertThat(outgoing.metadata()).containsEntry("primaryIntent", "TABLE_BOOKING");
        assertThat(outgoing.metadata()).containsEntry("pendingIntentsStored", true);
        assertThat(outgoing.metadata().get("pendingIntents")).asList().containsExactly("MENU_ASSETS");
        assertThat(outgoing.metadata().get("pendingIntentPrompts").toString()).contains("покажи винную карту");
        verify(fsmStorage).setPendingIntents(incoming.chatId(), java.util.List.of("MENU_ASSETS::покажи винную карту"));
        verify(menuAssetsScenario, never()).handle(any(), any(), any());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void executesStoredPendingSafeContentAfterScenarioCompletion() {
        IncomingMessage incoming = telegram("да");
        OutgoingMessage tipConfirmed = OutgoingMessage.of(
                incoming,
                "Готово, draft благодарности сохранен.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("SMART_TIP", "TIP_DRAFT_CONFIRMED", "RETURN_MAIN_MENU")
        );
        OutgoingMessage wineMenu = OutgoingMessage.of(
                incoming,
                "Да, отправляю: Винная карта.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                java.util.List.of("MENU_ASSETS", "MENU_ASSETS_DELIVERED", "RETURN_MAIN_MENU")
        ).withMetadata(java.util.Map.of(
                "scenario", "MenuAssetsScenario",
                "documents", java.util.List.of(java.util.Map.of(
                        "objectKey", "content/aeris-menu/wine.pdf",
                        "filename", "WINE MENU.pdf",
                        "caption", "Винная карта"
                ))
        ));

        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.TIP_CONFIRMATION);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.TIP_CONFIRMATION), eq(incoming.text())))
                .thenReturn(false);
        when(smartTipScenario.supports(eq(incoming), eq(BotState.TIP_CONFIRMATION), eq(incoming.text())))
                .thenReturn(true);
        when(smartTipScenario.handle(eq(incoming), eq(BotState.TIP_CONFIRMATION), eq(incoming.text())))
                .thenReturn(tipConfirmed);
        when(fsmStorage.getPendingIntents(incoming.chatId()))
                .thenReturn(java.util.List.of("MENU_ASSETS::покажи винную карту"));
        when(menuAssetsScenario.handle(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq("покажи винную карту")))
                .thenReturn(wineMenu);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.text()).contains("draft благодарности", "Винная карта");
        assertThat(outgoing.actions()).contains("COMPOSITE_INTENT_PLAN", "TIP_DRAFT_CONFIRMED", "MENU_ASSETS_DELIVERED");
        assertThat(outgoing.metadata()).containsEntry("compositePlan", "RESUME_PENDING_CONTENT");
        assertThat(outgoing.metadata().get("pendingIntentsExecuted")).asList().containsExactly("MENU_ASSETS");
        verify(fsmStorage).clearPendingIntents(incoming.chatId());
        verify(modelGateway, never()).generateText(any());
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.TIP_CONFIRMATION, outgoing);
    }

    @Test
    void asksToRecordVoiceAgainAfterFirstFailedTranscription() {
        IncomingMessage incoming = telegramVoice("");
        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(mainMenuScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(voiceTranscriptionRetryService.recordFailure(incoming.chatId())).thenReturn(1L);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.actions()).containsExactly("VOICE_RECEIVED", "TRANSCRIPTION_RETRY_REQUESTED");
        assertThat(outgoing.text()).contains("еще раз");
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    @Test
    void asksForTextAfterSecondFailedVoiceTranscription() {
        IncomingMessage incoming = telegramVoice("");
        when(fsmStorage.getState(incoming.chatId())).thenReturn(BotState.READY_FOR_DIALOG);
        when(firstTouchScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(tableBookingScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(menuAssetsScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(quietGuideScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(mainMenuScenario.supports(eq(incoming), eq(BotState.READY_FOR_DIALOG), eq(""))).thenReturn(false);
        when(voiceTranscriptionRetryService.recordFailure(incoming.chatId())).thenReturn(2L);

        OutgoingMessage outgoing = service.handle(incoming);

        assertThat(outgoing.actions()).containsExactly("VOICE_RECEIVED", "TRANSCRIPTION_FAILED_TWICE", "ASK_TEXT_INPUT");
        assertThat(outgoing.text()).contains("дважды", "текстом");
        verify(userEventProducer).publishIncomingMessage(incoming, BotState.READY_FOR_DIALOG, outgoing);
    }

    private IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(
                1773317437L,
                1773317437L,
                351,
                284069875,
                text,
                null,
                "Наталья",
                "Поединенко",
                "Poedinenko",
                "ru",
                false,
                "284069875"
        );
    }

    private IncomingMessage telegramVoice(String text) {
        return IncomingMessage.telegram(
                1773317437L,
                1773317437L,
                351,
                284069875,
                text,
                null,
                "Наталья",
                "Поединенко",
                "Poedinenko",
                "ru",
                false,
                "284069875",
                java.util.Map.of(
                        "mediaKind", "VOICE",
                        "transcriptionStatus", "FAILED",
                        "transcriptionReason", "STT command returned blank text"
                )
        );
    }
}
