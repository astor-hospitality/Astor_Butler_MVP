package museon_online.astor_butler.max.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.messenger.MessengerChatBinding;
import museon_online.astor_butler.domain.messenger.MessengerChatBindingRepository;
import museon_online.astor_butler.fsm.core.idempotency.IdempotencyService;
import museon_online.astor_butler.max.MaxBotSettings;
import museon_online.astor_butler.max.client.MaxApiException;
import museon_online.astor_butler.max.client.MaxBotApiClient;
import museon_online.astor_butler.max.client.MaxOutgoingMessage;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import museon_online.astor_butler.service.message.MessageGatewayService;
import museon_online.astor_butler.service.message.OutgoingMessage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * One MAX update through the same pipeline a Telegram message takes: idempotency check, chat binding (internal
 * chat id), {@link MessageGatewayService#handle} (FSM, scenarios, model, timeline, Kafka), then the answer rendered
 * for MAX and admin alerts relayed to the Telegram admin chat. Group chats are ignored unless enabled; messages from
 * bots are ignored. Telegram-only extras of the Telegram router (preview card, documents/video, voice replies,
 * hostess and review callbacks) are later phases of docs/architecture/MAX_ADAPTER_PLAN.md.
 */
@Slf4j
public class MaxRouter {

    static final String ERROR_TEXT = "⚠️ Произошла ошибка. Попробуйте позже.";

    private final MaxBotApiClient client;
    private final MaxUpdateMapper mapper;
    private final MaxReplyRenderer renderer;
    private final MessengerChatBindingRepository bindings;
    private final IdempotencyService idempotency;
    private final MessageGatewayService gateway;
    private final MaxAdminAlertRelay adminAlerts;
    private final MaxBotSettings settings;
    private final Duration pacing;

    public MaxRouter(
            MaxBotApiClient client,
            MaxUpdateMapper mapper,
            MaxReplyRenderer renderer,
            MessengerChatBindingRepository bindings,
            IdempotencyService idempotency,
            MessageGatewayService gateway,
            MaxAdminAlertRelay adminAlerts,
            MaxBotSettings settings
    ) {
        this(client, mapper, renderer, bindings, idempotency, gateway, adminAlerts, settings, Duration.ofMillis(550));
    }

    MaxRouter(
            MaxBotApiClient client,
            MaxUpdateMapper mapper,
            MaxReplyRenderer renderer,
            MessengerChatBindingRepository bindings,
            IdempotencyService idempotency,
            MessageGatewayService gateway,
            MaxAdminAlertRelay adminAlerts,
            MaxBotSettings settings,
            Duration pacing
    ) {
        this.client = client;
        this.mapper = mapper;
        this.renderer = renderer;
        this.bindings = bindings;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.adminAlerts = adminAlerts;
        this.settings = settings;
        this.pacing = pacing;
    }

    public void handle(JsonNode update) {
        Optional<MaxInbound> read = mapper.read(update);
        if (read.isEmpty()) {
            log.debug("MAX update ignored: type={}", update == null ? "" : update.path("update_type").asText(""));
            return;
        }
        MaxInbound inbound = read.get();
        if (inbound.callbackId() != null) {
            acknowledge(inbound.callbackId());
        }
        if (inbound.fromBot()) {
            return;
        }
        if (!inbound.dialog() && !settings.groupChatsEnabled()) {
            log.debug("MAX group chat update ignored: chatType={}", inbound.chatType());
            return;
        }
        if (inbound.text() == null && inbound.contactPhone() == null && !inbound.payload().containsKey("mediaKind")) {
            return;
        }
        if (!idempotency.accept("max:" + inbound.eventKey())) {
            log.info("MAX duplicate update ignored: type={}", inbound.updateType());
            return;
        }

        MessengerChatBinding binding = bindings.resolve(
                MessageChannel.MAX,
                inbound.chatId(),
                inbound.dialog() ? inbound.userId() : null,
                inbound.chatType().isBlank() ? "dialog" : inbound.chatType(),
                !inbound.dialog(),
                new MessengerChatBindingRepository.Profile(inbound.firstName(), inbound.lastName(), inbound.username(),
                        inbound.locale())
        );
        if (inbound.contactPhone() != null && inbound.dialog()) {
            bindings.saveContactPhone(MessageChannel.MAX, inbound.chatId(), inbound.contactPhone());
        }

        IncomingMessage incoming = mapper.toIncoming(inbound, binding.internalChatId());
        log.info("MAX incoming: type={}, chatId={}", inbound.updateType(), binding.internalChatId());
        OutgoingMessage outgoing;
        try {
            outgoing = gateway.handle(incoming);
        } catch (RuntimeException e) {
            // Same promise as TelegramExceptionHandler: the guest is told, the update is not retried.
            log.error("MAX update failed in the gateway: chatId={}, reason={}", binding.internalChatId(), e.toString(), e);
            if (inbound.dialog()) {
                sendQuietly(binding.externalChatId(), MaxOutgoingMessage.plain(ERROR_TEXT));
            }
            return;
        }
        send(binding.externalChatId(), outgoing, inbound.dialog());
        adminAlerts.forward(outgoing);
    }

    private void sendQuietly(long maxChatId, MaxOutgoingMessage message) {
        try {
            client.sendMessage(maxChatId, message);
        } catch (MaxApiException e) {
            log.warn("MAX error notice was not delivered: status={}", e.status());
        }
    }

    private void send(long maxChatId, OutgoingMessage outgoing, boolean dialog) {
        List<MaxOutgoingMessage> messages = renderer.render(outgoing, dialog);
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0 && !pause()) {
                return;
            }
            try {
                client.sendMessage(maxChatId, messages.get(i));
            } catch (MaxApiException e) {
                log.error("MAX reply was not delivered: status={}, code={}, reason={}", e.status(), e.code(), e.getMessage());
                return;
            }
        }
        if (outgoing != null) {
            log.info("MAX outgoing sent: chatId={}, nextState={}, actions={}", outgoing.chatId(), outgoing.nextState(),
                    outgoing.actions());
        }
    }

    /** MAX accepts at most 2 messages per second per chat; a split answer is paced to stay under it. */
    private boolean pause() {
        try {
            Thread.sleep(pacing.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void acknowledge(String callbackId) {
        try {
            client.answerCallback(callbackId, null);
        } catch (MaxApiException e) {
            log.warn("MAX callback was not acknowledged: status={}, reason={}", e.status(), e.getMessage());
        }
    }
}
