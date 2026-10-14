package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.config.AstorLinksProperties;
import museon_online.astor_butler.domain.consent.ConsentVaultService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirstTouchScenarioTest {

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private ConsentVaultService consentVaultService;

    @Mock
    private TableBookingDraftStorage tableBookingDraftStorage;

    @Mock
    private ChangeCancelDraftStorage changeCancelDraftStorage;

    private final AstorLinksProperties links = new AstorLinksProperties();

    private FirstTouchScenario scenario;

    @BeforeEach
    void setUp() {
        scenario = new FirstTouchScenario(fsmStorage, consentVaultService, tableBookingDraftStorage, changeCancelDraftStorage, links);
    }

    @Test
    void startsByRequestingContactAndConsent() {
        IncomingMessage incoming = telegram("/start", null);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.UNKNOWN, "/start");

        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        assertThat(outgoing.requestContact()).isTrue();
        assertThat(outgoing.html()).isTrue();
        assertThat(outgoing.text()).contains("Согласиться и поделиться контактом");
        assertThat(outgoing.text()).contains("<a href=\"" + links.getPolicy() + "\">политикой обработки персональных данных</a>");
        assertThat(outgoing.actions()).containsExactly("SAFE_RESTART", "REQUEST_CONTACT", "CONSENT_REQUIRED");
        verify(fsmStorage).clear(incoming.chatId());
        verify(fsmStorage).setState(421441838L, BotState.CONSENT_REQUIRED);
        verify(tableBookingDraftStorage).clear(incoming.chatId());
        verify(changeCancelDraftStorage).clear(incoming.chatId());
    }

    @Test
    void consentMessageLinksTheConfiguredPolicy() {
        links.setPolicy("https://example.test/policy.html");
        IncomingMessage incoming = telegram("/start", null);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.UNKNOWN, "/start");

        assertThat(outgoing.text())
                .contains("<a href=\"https://example.test/policy.html\">политикой обработки персональных данных</a>")
                .doesNotContain("michaelwelly.github.io");
    }

    @Test
    void startForKnownGuestResetsRuntimeAndReturnsReady() {
        IncomingMessage incoming = telegram("/start", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "/start");

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.text()).isEqualTo("AERIS · Батлер. Я на связи за столом: меню, вино, счёт, позвать официанта. Что подать?");
        assertThat(outgoing.actions()).containsExactly("SAFE_RESTART", "SHORT_GREETING", "OPEN_MENU");
        verify(fsmStorage).clear(incoming.chatId());
        verify(tableBookingDraftStorage).clear(incoming.chatId());
        verify(changeCancelDraftStorage).clear(incoming.chatId());
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void restartForKnownGuestClearsRuntimeDraftsAndShowsMainMenuText() {
        IncomingMessage incoming = telegram("/restart", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, "/restart");

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.removeKeyboard()).isTrue();
        assertThat(outgoing.text()).contains("Перезапустил диалог", "главное меню");
        assertThat(outgoing.actions()).containsExactly("SAFE_RESTART", "PREVIEW_REFRESHED", "OPEN_MENU");
        verify(fsmStorage).clear(incoming.chatId());
        verify(tableBookingDraftStorage).clear(incoming.chatId());
        verify(changeCancelDraftStorage).clear(incoming.chatId());
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void unknownTelegramGuestRequestsContactAndConsentWithoutStartCommand() {
        IncomingMessage incoming = telegram("Привет", null);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.UNKNOWN, "Привет");

        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        assertThat(outgoing.requestContact()).isTrue();
        assertThat(outgoing.actions()).containsExactly("SAFE_RESTART", "REQUEST_CONTACT", "CONSENT_REQUIRED");
        verify(fsmStorage).clear(incoming.chatId());
        verify(fsmStorage).setState(421441838L, BotState.CONSENT_REQUIRED);
    }

    @Test
    void storesConsentAndOpensMenuAfterContact() {
        IncomingMessage incoming = telegram("", "+79990000000");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.CONSENT_REQUIRED, "");

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.removeKeyboard()).isTrue();
        assertThat(outgoing.actions()).containsExactly("CONTACT_CAPTURED", "OPEN_MENU");
        verify(fsmStorage).setState(421441838L, BotState.READY_FOR_DIALOG);
        verify(consentVaultService).grantPrivacyPolicyFromTelegramContact(incoming);
    }

    @Test
    void keepsContactStateWhenGuestWritesBeforeConsent() {
        IncomingMessage incoming = telegram("А можно без контакта?", null);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.CONSENT_REQUIRED, "А можно без контакта?");

        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        assertThat(outgoing.requestContact()).isTrue();
        assertThat(outgoing.actions()).containsExactly(
                "PRE_AUTH_CONSENT_NUDGE",
                "REQUEST_CONTACT",
                "CONSENT_REQUIRED"
        );
        assertThat(outgoing.text()).contains("контакт");
        verifyNoInteractions(consentVaultService);
    }

    @Test
    void unknownMaxGuestGetsTheSameConsentGateAsTelegram() {
        IncomingMessage incoming = max("Привет", null);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.UNKNOWN, "Привет");

        assertThat(scenario.supports(incoming, BotState.UNKNOWN, "Привет")).isTrue();
        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        assertThat(outgoing.requestContact()).isTrue();
        verify(consentVaultService).hasGrantedMaxPrivacyPolicy(MAX_CHAT_ID);
        verify(fsmStorage).setState(MAX_CHAT_ID, BotState.CONSENT_REQUIRED);
    }

    @Test
    void maxGuestWithConsentRestartsIntoReadyWithoutTelegramLookups() {
        IncomingMessage incoming = max("/start", null);
        when(consentVaultService.hasGrantedMaxPrivacyPolicy(MAX_CHAT_ID)).thenReturn(true);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.AI_FALLBACK, "/start");

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        verify(consentVaultService, never()).hasGrantedPrivacyPolicy(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void maxContactStoresConsentThroughTheMaxRecord() {
        IncomingMessage incoming = max("", "+79990000000");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.CONSENT_REQUIRED, "");

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        verify(consentVaultService).grantPrivacyPolicyFromMaxContact(incoming);
        verify(consentVaultService, never()).grantPrivacyPolicyFromTelegramContact(incoming);
    }

    @Test
    void webVisitorInUnknownStateIsNotTreatedAsAMessengerGuest() {
        IncomingMessage incoming = new IncomingMessage(MessageChannel.WEB, "web:anon:s1", 9_000_000_000_001L, null, null,
                null, "Привет", null, null, null, null, null, false, "c1", java.time.Instant.now(), java.util.Map.of());

        assertThat(scenario.supports(incoming, BotState.UNKNOWN, "Привет")).isFalse();
    }

    @Test
    void withTheStaffHandoffOnThePlainStaffLinkIsAStartForAGuestWhoAlreadyConsented() {
        scenario.setStaffHandoffEnabled(true);
        when(consentVaultService.hasGrantedPrivacyPolicy(284069847L)).thenReturn(true);
        IncomingMessage incoming = telegram("/start h_anna_t5", null);

        assertThat(scenario.supports(incoming, BotState.READY_FOR_DIALOG, "/start h_anna_t5")).isTrue();
        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, "/start h_anna_t5");

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.actions()).contains("SAFE_RESTART", "OPEN_MENU");
        verify(fsmStorage).clear(incoming.chatId());
        verify(tableBookingDraftStorage).clear(incoming.chatId());
    }

    @Test
    void withTheStaffHandoffOffTheStaffLinksAreNotStartsExactlyAsBefore() {
        // The default: /start h_… and the lunch link are not the first touch for a guest who is already in the dialogue.
        assertThat(scenario.supports(telegram("/start h_anna_t5", null), BotState.READY_FOR_DIALOG, "/start h_anna_t5")).isFalse();
        assertThat(scenario.supports(telegram("/start lunch_aeris_hanna", null), BotState.READY_FOR_DIALOG, "/start lunch_aeris_hanna")).isFalse();
        scenario.setStaffHandoffEnabled(true);
        // The lunch link stays with the lunch scenario even with the flag on.
        assertThat(scenario.supports(telegram("/start lunch_aeris_hanna", null), BotState.READY_FOR_DIALOG, "/start lunch_aeris_hanna")).isFalse();
        verifyNoInteractions(fsmStorage, consentVaultService);
    }

    private IncomingMessage telegram(String text, String phone) {
        return IncomingMessage.telegram(
                421441838L,
                284069847L,
                10,
                20,
                text,
                phone,
                "Наталья",
                "Тестова",
                "natalia_test",
                "ru",
                false,
                "20"
        );
    }

    private static final long MAX_CHAT_ID = 8_000_000_000_007L;

    private IncomingMessage max(String text, String phone) {
        return IncomingMessage.max(MAX_CHAT_ID, 9_123_456L, text, phone, "Анна", null, null, "ru", false, "max-1",
                java.util.Map.of("maxChatId", 555L));
    }

    @Test
    void knownGuestStartNamesTheLastLunchWhenTheGreetingHasOne() {
        IncomingMessage incoming = telegram("/start", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);
        museon_online.astor_butler.domain.journey.RepeatLunchGreeting greeting = org.mockito.Mockito.mock(museon_online.astor_butler.domain.journey.RepeatLunchGreeting.class);
        when(greeting.text(incoming)).thenReturn(java.util.Optional.of("С возвращением, Мария! В прошлый раз у вас был Капучино. Повторить или собрать заново?"));
        scenario.setRepeatLunchGreeting(greeting);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, "/start");

        assertThat(outgoing.text()).isEqualTo("С возвращением, Мария! В прошлый раз у вас был Капучино. Повторить или собрать заново?");
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
    }

    @Test
    void knownGuestStartKeepsTheUsualGreetingWithoutALastLunch() {
        IncomingMessage incoming = telegram("/start", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);
        museon_online.astor_butler.domain.journey.RepeatLunchGreeting greeting = org.mockito.Mockito.mock(museon_online.astor_butler.domain.journey.RepeatLunchGreeting.class);
        when(greeting.text(incoming)).thenReturn(java.util.Optional.empty());
        scenario.setRepeatLunchGreeting(greeting);

        assertThat(scenario.handle(incoming, BotState.READY_FOR_DIALOG, "/start").text()).isEqualTo("AERIS · Батлер. Я на связи за столом: меню, вино, счёт, позвать официанта. Что подать?");
    }

    @Test
    void startWithoutContextIsShortWithThreeButtons() {
        IncomingMessage incoming = telegram("/start", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, "/start");

        assertThat(outgoing.text()).isEqualTo("AERIS · Батлер. Я на связи за столом: меню, вино, счёт, позвать официанта. Что подать?");
        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.requestContact()).isFalse();
        assertThat(outgoing.metadata().get("replyKeyboardRows"))
                .isEqualTo(java.util.List.of(java.util.List.of("Меню ланча"), java.util.List.of("Позвать официанта", "Счёт")));
        // No picture or file rides on the short first screen: the visiting card lives in the pinned message only.
        assertThat(outgoing.metadata()).doesNotContainKeys("documentUrl", "videoUrl", "photoUrl");
        assertThat(outgoing.actions()).doesNotContain("PREVIEW_REFRESHED");
    }

    @Test
    void theThreeButtonsSpeakWordsTheExistingFlowsAlreadyTake() {
        // «Меню ланча» → BusinessLunchScenario (any idle text with «ланч»); «Позвать официанта» → the seated table request;
        // «Счёт» → the guest bill flow, decided before any scenario.
        assertThat(FirstTouchScenario.SHORT_GREETING_BUTTONS.get(0).get(0).toLowerCase()).contains("ланч");
        assertThat(museon_online.astor_butler.domain.journey.talk.TableRequest.recognise("Позвать официанта"))
                .contains(museon_online.astor_butler.domain.journey.talk.TableRequest.WAITER);
        assertThat(museon_online.astor_butler.domain.billing.GuestBillIntent.matches("Счёт")).isTrue();
    }

    @Test
    void startForAGuestWithALastLunchStillGetsTheWelcomeBack() {
        IncomingMessage incoming = telegram("/start", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);
        museon_online.astor_butler.domain.journey.RepeatLunchGreeting greeting = org.mockito.Mockito.mock(museon_online.astor_butler.domain.journey.RepeatLunchGreeting.class);
        when(greeting.text(incoming)).thenReturn(java.util.Optional.of("С возвращением, Наталья! В прошлый раз у вас был Борщ. Повторить или собрать заново?"));
        scenario.setRepeatLunchGreeting(greeting);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, "/start");

        assertThat(outgoing.text()).startsWith("С возвращением");
        assertThat(outgoing.metadata()).doesNotContainKey("replyKeyboardRows");
    }

    @Test
    void lunchDeepLinkStartIsUntouched() {
        // A known guest whose state expired arrives by the lunch link: the first touch answers as before and the lunch
        // scenario takes the draft over (ScenarioRouter.continueAfterFirstTouch); no short screen, no extra buttons.
        IncomingMessage incoming = telegram("/start lunch_aeris_s2_p2", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.UNKNOWN, "/start lunch_aeris_s2_p2");

        assertThat(outgoing.text()).isEqualTo("С возвращением! Чем помочь: стол, бизнес-ланч, меню, афиша или помощь команды. Выберите кнопкой или напишите.");
        assertThat(outgoing.metadata()).doesNotContainKey("replyKeyboardRows");
        assertThat(outgoing.actions()).containsExactly("SAFE_RESTART", "PREVIEW_REFRESHED", "OPEN_MENU");
    }

    @Test
    void staffHandoffStartIsUntouched() {
        scenario.setStaffHandoffEnabled(true);
        IncomingMessage incoming = telegram("/start h_anna_t5", null);
        when(consentVaultService.hasGrantedPrivacyPolicy(incoming.telegramUserId())).thenReturn(true);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, "/start h_anna_t5");

        assertThat(outgoing.text()).startsWith("С возвращением! Чем помочь");
        assertThat(outgoing.metadata()).doesNotContainKey("replyKeyboardRows");
    }
}
