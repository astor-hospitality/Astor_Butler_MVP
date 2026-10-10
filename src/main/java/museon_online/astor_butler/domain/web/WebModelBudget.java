package museon_online.astor_butler.domain.web;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Cost guard for the paid model path of anonymous web guests: free text that reaches the LLM is kept short,
 * and one address gets a daily budget of model calls. Telegram guests are not affected. Fails open on Redis errors.
 */
@Service
@Slf4j
public class WebModelBudget {

    public static final String CLIENT_KEY = "webClientKey";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${astor.web.model.text-max-chars:600}")
    private int textMaxChars;

    @Value("${astor.web.model.calls-per-day-per-ip:200}")
    private long callsPerDayPerIp;

    public WebModelBudget(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    public Decision check(IncomingMessage incoming, String text) {
        if (incoming == null || incoming.channel() != MessageChannel.WEB) {
            return Decision.allow();
        }
        String safeText = text == null ? "" : text;
        if (textMaxChars > 0 && safeText.codePointCount(0, safeText.length()) > textMaxChars) {
            count("text_too_long");
            return Decision.deny("text_too_long", tooLongText());
        }
        if (callsPerDayPerIp <= 0) {
            return Decision.allow();
        }
        String key = clientKey(incoming);
        try {
            String redisKey = "web-chat:model:day:" + key;
            Long value = redisTemplate.opsForValue().increment(redisKey);
            if (value != null && value == 1L) {
                redisTemplate.expire(redisKey, Duration.ofDays(1));
            }
            if (value != null && value > callsPerDayPerIp) {
                count("daily_budget");
                return Decision.deny("daily_budget", budgetText());
            }
        } catch (RuntimeException e) {
            log.warn("Web model budget failed open: key={}, reason={}", key, e.getMessage());
            count("error_open");
        }
        count("allowed");
        return Decision.allow();
    }

    private String clientKey(IncomingMessage incoming) {
        Object key = incoming.payload() == null ? null : incoming.payload().get(CLIENT_KEY);
        if (key != null && !key.toString().isBlank()) {
            return key.toString().replaceAll("[^a-zA-Z0-9:_.-]", "_");
        }
        return "chat:" + incoming.chatId();
    }

    private String tooLongText() {
        return "Сообщение получилось длинным. Напишите, пожалуйста, короче — до " + textMaxChars
                + " символов — или выберите действие кнопкой: бронь стола, меню, афиша.";
    }

    private String budgetText() {
        return "На сегодня лимит свободных вопросов с сайта исчерпан. Выберите действие кнопкой — бронь стола, меню, афиша — "
                + "или напишите команде заведения, и мы ответим лично.";
    }

    private void count(String outcome) {
        meterRegistry.counter("astor.web_chat.model_budget", "outcome", outcome).increment();
    }

    public record Decision(boolean allowed, String reason, String replyText) {
        public static Decision allow() {
            return new Decision(true, "", "");
        }

        public static Decision deny(String reason, String replyText) {
            return new Decision(false, reason, replyText);
        }
    }
}
