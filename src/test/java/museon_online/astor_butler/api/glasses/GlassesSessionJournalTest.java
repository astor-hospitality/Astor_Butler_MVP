package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GlassesSessionJournalTest {
    private static final String SESSION = "80d26cf1-5139-4121-a4ca-dfb14aac225c";
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("venue", "staff");
    private final GlassesS3Storage storage = mock(GlassesS3Storage.class);

    private static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-07T06:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final GlassesSessionJournal journal = new GlassesSessionJournal(storage, clock, false);

    private GlassesPhotoContext context(String stage) {
        return new GlassesPhotoContext(SESSION, "BUSINESS_LUNCH_TWO", stage, 3);
    }

    @Test void assistsAndClientEventsShareOneSessionInOrderOfArrival() {
        journal.recordEvents(scope, "11111111-1111-4111-8111-111111111111", SESSION, "BUSINESS_LUNCH_TWO",
                List.of(new GlassesSessionJournal.ClientEvent("2026-10-07T06:00:01Z", "STEP_STARTED", "PLACE_SETTINGS", null)));
        clock.now = clock.now.plusSeconds(5);
        journal.recordAssist(scope, context("PLACE_SETTINGS"), "ff5a8c58-bb60-43f4-b542-1e26c8b96581", "image", "Два прибора видны.", null, 1200, true);
        clock.now = clock.now.plusSeconds(5);
        journal.recordAssist(scope, context("PLACE_SETTINGS"), "ff5a8c58-bb60-43f4-b542-1e26c8b96582", "image", null, "VISION_UNAVAILABLE", 45000, false);

        var session = journal.session(scope, SESSION);
        assertThat(session.scenarioCode()).isEqualTo("BUSINESS_LUNCH_TWO");
        assertThat(session.entries()).extracting(GlassesSessionJournal.Entry::type).containsExactly("STEP_STARTED", "ASSIST", "ASSIST");
        assertThat(session.entries().get(1).text()).isEqualTo("Два прибора видны.");
        assertThat(session.entries().get(1).archived()).isTrue();
        assertThat(session.entries().get(2).error()).isEqualTo("VISION_UNAVAILABLE");
        assertThat(session.entries().get(2).archived()).isNull();
        var summary = journal.sessions(scope).get(0);
        assertThat(summary.requests()).isEqualTo(2);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.clientEvents()).isEqualTo(1);
        assertThat(journal.sessions(new GlassesAccess.Scope("other", "staff"))).isEmpty();
    }

    @Test void aBatchIsIdempotentByRequestIdAndRejectsADifferentBodyUnderTheSameId() {
        var events = List.of(new GlassesSessionJournal.ClientEvent("2026-10-07T06:00:01Z", "CALL_STARTED", null, null));
        assertThat(journal.recordEvents(scope, "11111111-1111-4111-8111-111111111111", SESSION, "BUSINESS_LUNCH_TWO", events)).isEqualTo(1);
        assertThat(journal.recordEvents(scope, "11111111-1111-4111-8111-111111111111", SESSION, "BUSINESS_LUNCH_TWO", events)).isEqualTo(1);
        assertThat(journal.session(scope, SESSION).entries()).hasSize(1);
        var changed = List.of(new GlassesSessionJournal.ClientEvent("2026-10-07T06:00:02Z", "CALL_ENDED", null, "x"));
        assertThatThrownBy(() -> journal.recordEvents(scope, "11111111-1111-4111-8111-111111111111", SESSION, "BUSINESS_LUNCH_TWO", changed))
                .isInstanceOf(GlassesFailure.class).satisfies(e -> assertThat(((GlassesFailure) e).code).isEqualTo("REQUEST_ID_CONFLICT"));
        assertThatThrownBy(() -> journal.recordEvents(scope, "22222222-2222-4222-8222-222222222222", SESSION, "BUSINESS_LUNCH_TWO", changed))
                .isInstanceOf(GlassesFailure.class).satisfies(e -> assertThat(((GlassesFailure) e).code).isEqualTo("SESSION_CONFLICT"));
    }

    @Test void sessionsExpireAfterADayAndTheOldestIsEvictedPastTheCap() {
        for (int i = 0; i < GlassesSessionJournal.MAX_SESSIONS + 1; i++) {
            String id = UUID.nameUUIDFromBytes(("s" + i).getBytes(StandardCharsets.UTF_8)).toString();
            journal.recordAssist(scope, new GlassesPhotoContext(id, "BUSINESS_LUNCH_TWO", "FINAL_CHECK", 1), id, "image", "ok", null, 10, false);
            clock.now = clock.now.plusSeconds(1);
        }
        assertThat(journal.sessions(scope)).hasSize(GlassesSessionJournal.MAX_SESSIONS);
        assertThat(journal.session(scope, UUID.nameUUIDFromBytes("s0".getBytes(StandardCharsets.UTF_8)).toString())).isNull();
        clock.now = clock.now.plus(Duration.ofHours(25));
        assertThat(journal.sessions(scope)).isEmpty();
    }

    @Test void aFullSessionDropsAssistEntriesQuietlyButRefusesABatchThatWouldOverflow() {
        var context = context("WATER_MENU");
        for (int i = 0; i < GlassesSessionJournal.MAX_ENTRIES; i++) {
            journal.recordAssist(scope, context, UUID.randomUUID().toString(), "text", "ok", null, 1, false);
        }
        journal.recordAssist(scope, context, UUID.randomUUID().toString(), "text", "dropped", null, 1, false);
        assertThat(journal.session(scope, SESSION).entries()).hasSize(GlassesSessionJournal.MAX_ENTRIES);
        assertThatThrownBy(() -> journal.recordEvents(scope, "11111111-1111-4111-8111-111111111111", SESSION, "BUSINESS_LUNCH_TWO",
                List.of(new GlassesSessionJournal.ClientEvent("2026-10-07T06:00:01Z", "SESSION_FINISHED", null, null))))
                .satisfies(e -> assertThat(((GlassesFailure) e).code).isEqualTo("SESSION_FULL"));
    }

    @Test void changedSessionsAreWrittenToThePrivateArchiveAndRetriedAfterAFailure() throws Exception {
        journal.recordAssist(scope, context("TABLE_PREPARE"), "ff5a8c58-bb60-43f4-b542-1e26c8b96581", "text", "Добрый день.", null, 300, true);
        when(storage.writeJournal(eq(scope), eq(SESSION), any())).thenReturn(false, true);
        journal.flush();
        journal.flush();
        journal.flush();
        verify(storage, times(2)).writeJournal(eq(scope), eq(SESSION), argThat(bytes -> {
            String json = new String(bytes, StandardCharsets.UTF_8);
            return json.contains("\"sessionId\":\"" + SESSION + "\"") && json.contains("Добрый день.") && !json.contains("imageBase64");
        }));
    }

    @Test void anUnknownSessionIsReadBackFromTheArchiveWhenReadingIsAllowed() {
        String stored = "{\"sessionId\":\"" + SESSION + "\",\"scenarioCode\":\"SHIFT\",\"startedAt\":\"2026-10-06T10:00:00Z\","
                + "\"updatedAt\":\"2026-10-06T10:30:00Z\",\"entries\":[{\"at\":\"2026-10-06T10:00:00Z\",\"type\":\"STEP_STARTED\",\"stageCode\":\"TABLE_PREPARE\"}],\"batches\":{}}";
        when(storage.readJournal(scope, SESSION)).thenReturn(stored.getBytes(StandardCharsets.UTF_8));
        var session = journal.session(scope, SESSION);
        assertThat(session.entries()).hasSize(1);
        assertThat(session.entries().get(0).stageCode()).isEqualTo("TABLE_PREPARE");
        when(storage.readJournal(scope, "11111111-1111-4111-8111-111111111111")).thenReturn("{not json".getBytes(StandardCharsets.UTF_8));
        assertThat(journal.session(scope, "11111111-1111-4111-8111-111111111111")).isNull();
        when(storage.readJournal(scope, "22222222-2222-4222-8222-222222222222")).thenReturn(null);
        assertThat(journal.session(scope, "22222222-2222-4222-8222-222222222222")).isNull();
    }
}
