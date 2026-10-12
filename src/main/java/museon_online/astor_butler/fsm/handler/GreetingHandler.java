package museon_online.astor_butler.fsm.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.config.AstorLinksProperties;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.core.CommandContext;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.telegram.utils.TelegramSender;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

/**
 * Обрабатывает /start — визитка Astor Butler.
 */
    @RequiredArgsConstructor
    @Slf4j
    @Deprecated(forRemoval = false)
    public class GreetingHandler implements FSMHandler {

        private final TelegramSender sender;
        private final FSMStorage storage;
        private final ModelGateway modelGateway;
        private final AstorLinksProperties links;

        @Override
        public BotState getState() {
            return BotState.GREETING;
        }

        @Override
        public void handle(CommandContext ctx) {
            Long chatId = ctx.getChatId();
            String userName = ctx.getFirstName();

            storage.setState(chatId, BotState.GREETING);

            log.info("🟢 [FSM] State set to GREETING (chatId={})", chatId);

            String prompt = String.format(
                    "Ты вежливый цифровой дворецкий. " +
                    "Одним предложением поприветствуй пользователя по имени %s.",
                    userName
            );

            try {
                log.info("🧠 [LLM] Sending prompt (chatId={})", chatId);
                log.debug("🧠 [LLM] Prompt text: {}", prompt);

                long startedAt = System.nanoTime();

                ModelTextResponse modelResponse = modelGateway.generateText(ModelTextRequest.of(
                        prompt,
                        "Greeting",
                        BotState.GREETING.name(),
                        "first-touch-greeting"
                ));
                String llmResponse = modelResponse.text();

                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

                log.info(
                        "🧠 [LLM] Response received (chatId={}, duration={} ms)",
                        chatId,
                        durationMs
                );

                log.debug(
                        "🧠 [LLM] Response text: {}",
                        llmResponse.length() > 300
                                ? llmResponse.substring(0, 300) + "…"
                                : llmResponse
                );

                String finalText =
                        llmResponse + "\n\n" +
                        "Чтобы продолжить, отправьте, пожалуйста, свой контакт.\n\n" +
                        "Продолжая, вы соглашаетесь с " +
                        "<a href=\"" + links.getPolicy() + "\">политикой обработки персональных данных</a>.";

                KeyboardButton shareContact = KeyboardButton.builder()
                        .text("📱 Поделиться контактом")
                        .requestContact(true)
                        .build();

                ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                        .keyboard(List.of(new KeyboardRow(List.of(shareContact))))
                        .resizeKeyboard(true)
                        .oneTimeKeyboard(true)
                        .build();

                sender.sendHtml(chatId, finalText, keyboard);

                log.info("✅ [FSM] GREETING message sent, waiting for contact");

            } catch (Exception e) {
                log.error("❌ [FSM] GREETING → LLM error", e);

                String fallbackText =
                        "\n\n" +
                        "Чтобы продолжить, отправьте, пожалуйста, свой контакт.\n\n" +
                        "Продолжая, вы соглашаетесь с " +
                        "<a href=\"" + links.getPolicy() + "\">политикой обработки персональных данных</a>.";

                KeyboardButton shareContact = KeyboardButton.builder()
                        .text("📱 Поделиться контактом")
                        .requestContact(true)
                        .build();

                ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                        .keyboard(List.of(new KeyboardRow(List.of(shareContact))))
                        .resizeKeyboard(true)
                        .oneTimeKeyboard(true)
                        .build();

                sender.sendText(chatId, fallbackText, keyboard);
            }
        }
    }
