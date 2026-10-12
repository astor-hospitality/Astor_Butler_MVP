package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The manual mode, the hostess card ids and the "already with the manager" marks live in Redis, next to the FSM state. */
class BookingHostessStateStorageTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final BookingHostessStateStorage storage = new BookingHostessStateStorage(redis);

    @BeforeEach
    void redisOperations() {
        ReflectionTestUtils.setField(storage, "keyPrefix", "astor");
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void autoModeWhileNothingWasEverSwitched() {
        when(hashes.entries("astor:booking:hostess:manual-mode")).thenReturn(Map.of());

        HostessManualMode mode = storage.manualMode();

        assertThat(mode.manual()).isFalse();
        assertThat(mode.switchedBy()).isNull();
    }

    @Test
    void manualModeIsStoredWithReasonWhoAndWhenAndReadBack() {
        Instant at = Instant.parse("2026-10-12T15:00:00Z");
        storage.saveManualMode(new HostessManualMode(true, "перегружены", "@dasha", at));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(eq("astor:booking:hostess:manual-mode"), written.capture());
        assertThat(written.getValue()).containsEntry("manual", "true")
                .containsEntry("reason", "перегружены")
                .containsEntry("switchedBy", "@dasha")
                .containsEntry("switchedAt", "2026-10-12T15:00:00Z");

        when(hashes.entries("astor:booking:hostess:manual-mode")).thenReturn(Map.copyOf(written.getValue()));
        HostessManualMode mode = storage.manualMode();
        assertThat(mode.manual()).isTrue();
        assertThat(mode.reason()).isEqualTo("перегружены");
        assertThat(mode.switchedBy()).isEqualTo("@dasha");
        assertThat(mode.switchedAt()).isEqualTo(at);
    }

    @Test
    void theHostessCardIsRememberedPerOrderForAWhile() {
        storage.rememberHostessCard(44L, "500", 321);
        verify(values).set(eq("astor:booking:hostess:card:44"), eq("500:321"), any(Duration.class));

        when(values.get("astor:booking:hostess:card:44")).thenReturn("500:321");
        assertThat(storage.hostessCard(44L)).contains(new BookingHostessStateStorage.CardRef("500", 321));

        when(values.get("astor:booking:hostess:card:45")).thenReturn(null);
        assertThat(storage.hostessCard(45L)).isEmpty();
    }

    @Test
    void anOrderIsMarkedAsEscalatedOnlyOnce() {
        when(values.setIfAbsent(eq("astor:booking:hostess:escalated:44"), anyString(), any(Duration.class)))
                .thenReturn(true, false);

        assertThat(storage.markEscalated(44L)).isTrue();
        assertThat(storage.markEscalated(44L)).isFalse();
    }

    @Test
    void theGuestCardIsMarkedSentOnceAndForgottenWithTheCard() {
        when(values.setIfAbsent(eq("astor:booking:guest-card:sent:44"), anyString(), eq(BookingHostessStateStorage.CARD_TTL)))
                .thenReturn(true, false);

        assertThat(storage.markGuestCardSent(44L)).isTrue();
        assertThat(storage.markGuestCardSent(44L)).isFalse();
        assertThat(storage.markGuestCardSent(null)).isFalse();
        verify(values, times(2)).setIfAbsent(eq("astor:booking:guest-card:sent:44"), anyString(), any(Duration.class));

        when(redis.hasKey("astor:booking:hostess:escalated:44")).thenReturn(true);
        assertThat(storage.escalated(44L)).isTrue();
    }

    @Test
    void anEditPanelIsStoredWithItsMessageAndWhoOpenedItForAWhile() {
        Instant at = Instant.parse("2026-10-12T15:00:00Z");
        storage.startEditSession(44L, new BookingHostessStateStorage.EditSession("500", 321, "@dasha", at, ""), Duration.ofMinutes(10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(redis).delete("astor:booking:hostess:edit:44");
        verify(hashes).putAll(eq("astor:booking:hostess:edit:44"), written.capture());
        verify(redis).expire("astor:booking:hostess:edit:44", Duration.ofMinutes(10));
        assertThat(written.getValue()).containsEntry("chatId", "500")
                .containsEntry("messageId", "321")
                .containsEntry("startedBy", "@dasha")
                .containsEntry("startedAt", "2026-10-12T15:00:00Z")
                .containsEntry("log", "");

        when(hashes.entries("astor:booking:hostess:edit:44")).thenReturn(Map.copyOf(written.getValue()));
        BookingHostessStateStorage.EditSession session = storage.editSession(44L).orElseThrow();
        assertThat(session.chatId()).isEqualTo("500");
        assertThat(session.messageId()).isEqualTo(321);
        assertThat(session.startedBy()).isEqualTo("@dasha");
        assertThat(session.startedAt()).isEqualTo(at);
        assertThat(session.logLines()).isEmpty();

        when(hashes.entries("astor:booking:hostess:edit:45")).thenReturn(Map.of());
        assertThat(storage.editSession(45L)).isEmpty();
        when(hashes.entries("astor:booking:hostess:edit:46")).thenReturn(Map.of("chatId", "500", "messageId", "oops"));
        assertThat(storage.editSession(46L)).isEmpty();
    }

    @Test
    void aPressKeepsTheLivePanelAliveButDoesNotReviveAnExpiredOne() {
        when(redis.hasKey("astor:booking:hostess:edit:44")).thenReturn(true);
        when(redis.expire("astor:booking:hostess:edit:44", Duration.ofMinutes(10))).thenReturn(true);
        assertThat(storage.touchEditSession(44L, Duration.ofMinutes(10))).isTrue();

        when(redis.hasKey("astor:booking:hostess:edit:45")).thenReturn(false);
        assertThat(storage.touchEditSession(45L, Duration.ofMinutes(10))).isFalse();
        verify(redis, never()).expire(eq("astor:booking:hostess:edit:45"), any(Duration.class));
    }

    @Test
    void changesAreAppendedToThePanelsJournalWhileItLives() {
        when(redis.hasKey("astor:booking:hostess:edit:44")).thenReturn(true);
        when(hashes.get("astor:booking:hostess:edit:44", "log")).thenReturn("", "13:05 @dasha — стол 5 → 7");

        storage.appendEditLog(44L, "13:05 @dasha — стол 5 → 7", Duration.ofMinutes(10));
        storage.appendEditLog(44L, "13:06 @dasha — гостей 2 → 3", Duration.ofMinutes(10));

        verify(hashes).put("astor:booking:hostess:edit:44", "log", "13:05 @dasha — стол 5 → 7");
        verify(hashes).put("astor:booking:hostess:edit:44", "log", "13:05 @dasha — стол 5 → 7\n13:06 @dasha — гостей 2 → 3");
        verify(redis, times(2)).expire("astor:booking:hostess:edit:44", Duration.ofMinutes(10));

        when(redis.hasKey("astor:booking:hostess:edit:45")).thenReturn(false);
        storage.appendEditLog(45L, "something", Duration.ofMinutes(10));
        verify(hashes, never()).put(eq("astor:booking:hostess:edit:45"), any(), any());

        assertThat(new BookingHostessStateStorage.EditSession("500", 1, null, null, "a\nb").logLines()).containsExactly("a", "b");
    }

    @Test
    void closingThePanelDropsIt() {
        storage.endEditSession(44L);
        verify(redis).delete("astor:booking:hostess:edit:44");
    }
}
