package museon_online.astor_butler.domain.web;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebChatRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${astor.web.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${astor.web.rate-limit.max-per-minute:12}")
    private long maxPerMinute;

    @Value("${astor.web.rate-limit.burst-window-seconds:10}")
    private long burstWindowSeconds;

    @Value("${astor.web.rate-limit.max-burst:4}")
    private long maxBurst;

    /** Second window keyed by client IP: a browser rotating sessionIds still shares one address. 0 disables it. */
    @Value("${astor.web.rate-limit.max-per-minute-per-ip:60}")
    private long maxPerMinutePerIp;

    /** How many never-seen sessionIds one address may open per minute: caps web_sessions growth and id rotation. 0 disables it. */
    @Value("${astor.web.rate-limit.max-new-sessions-per-minute-per-ip:5}")
    private long maxNewSessionsPerMinutePerIp;

    public Decision check(String clientIp, String externalUserId, Long chatId, Map<String, Object> payload) {
        if (!enabled) {
            count("disabled");
            return Decision.allow();
        }
        String key = clientKey(clientIp, externalUserId, chatId, payload);
        try {
            Decision fresh = newSessionWindow(clientIp, string(payload == null ? null : payload.get("sessionId")));
            if (!fresh.allowed()) {
                return fresh;
            }
            Decision burst = incrementWindow("web-chat:rate:burst:" + key, maxBurst, Duration.ofSeconds(Math.max(1, burstWindowSeconds)), "burst");
            if (!burst.allowed()) {
                return burst;
            }
            Decision minute = incrementWindow("web-chat:rate:minute:" + key, maxPerMinute, Duration.ofMinutes(1), "minute");
            if (!minute.allowed()) {
                return minute;
            }
            if (maxPerMinutePerIp > 0 && clientIp != null && !clientIp.isBlank() && !key.startsWith("ip:")) {
                Decision ip = incrementWindow("web-chat:rate:ip:" + sanitize(clientIp), maxPerMinutePerIp, Duration.ofMinutes(1), "ip");
                if (!ip.allowed()) {
                    return ip;
                }
            }
            count("allowed");
            return Decision.allow();
        } catch (RuntimeException e) {
            log.warn("Web chat rate limiter failed open: key={}, reason={}", key, e.getMessage());
            count("error_open");
                return Decision.allow();
        }
    }

    private Decision newSessionWindow(String clientIp, String sessionId) {
        if (maxNewSessionsPerMinutePerIp <= 0 || sessionId.isBlank() || clientIp == null || clientIp.isBlank()) {
            return Decision.allow();
        }
        String seenKey = "web-chat:session-seen:" + sanitize(sessionId);
        Boolean fresh = redisTemplate.opsForValue().setIfAbsent(seenKey, "1", Duration.ofDays(1));
        if (!Boolean.TRUE.equals(fresh)) {
            return Decision.allow();
        }
        Decision decision = incrementWindow("web-chat:rate:newsessions:" + sanitize(clientIp), maxNewSessionsPerMinutePerIp, Duration.ofMinutes(1), "new_sessions");
        if (!decision.allowed()) {
            // Not admitted: forget the mark so the same id counts as new again once the window has passed.
            redisTemplate.delete(seenKey);
        }
        return decision;
    }

    private Decision incrementWindow(String key, long limit, Duration ttl, String scope) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redisTemplate.expire(key, ttl);
        }
        long count = value == null ? 1L : value;
        if (count > Math.max(1, limit)) {
            count("denied_" + scope);
            return Decision.deny(scope, ttl.toSeconds());
        }
        return Decision.allow();
    }

    private String clientKey(String clientIp, String externalUserId, Long chatId, Map<String, Object> payload) {
        String sessionId = string(payload == null ? null : payload.get("sessionId"));
        if (!sessionId.isBlank()) {
            return "session:" + sanitize(sessionId);
        }
        if (externalUserId != null && !externalUserId.isBlank()) {
            return "external:" + sanitize(externalUserId);
        }
        if (chatId != null) {
            return "chat:" + chatId;
        }
        return "ip:" + sanitize(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
    }

    private void count(String outcome) {
        meterRegistry.counter("astor.web_chat.rate_limit", "outcome", outcome).increment();
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9:_.-]", "_");
    }

    private String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public record Decision(boolean allowed, String reason, long retryAfterSeconds) {
        public static Decision allow() {
            return new Decision(true, "", 0);
        }

        public static Decision deny(String reason, long retryAfterSeconds) {
            return new Decision(false, reason, Math.max(1, retryAfterSeconds));
        }
    }
}
