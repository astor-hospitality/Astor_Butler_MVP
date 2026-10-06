package com.astor.glasses.core;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The same checks as wear-greeting.m, wake-policy.m, call-policy.m and gesture-policy.m of the iPhone client. */
public class PoliciesTest {

    @Test public void greetingFollowsAWearTransitionOnceWithCooldownAndExpiry() {
        long t = 1_000_000;
        WearGreeting greeting = new WearGreeting();
        assertFalse(greeting.consume(t, true));
        greeting.observeStatus(0, t);
        assertFalse(greeting.consume(t, true));
        greeting.observeStatus(3, t);
        assertFalse("busy phone does not speak", greeting.consume(t, false));
        assertTrue(greeting.consume(t + 1_000, true));
        greeting.observeStatus(1, t + 2_000);
        assertFalse("still worn is not a new event", greeting.consume(t, true));
        greeting.observeStatus(0, t + 3_000);
        greeting.observeStatus(3, t + 4_000);
        assertFalse("cooldown", greeting.consume(t, true));
        greeting.observeStatus(0, t + 31_000);
        greeting.observeStatus(3, t + 32_000);
        assertTrue(greeting.consume(t + 32_000, true));
        greeting.observeStatus(0, t + 70_000);
        greeting.observeStatus(3, t + 71_000);
        assertFalse("a greeting that waited too long is dropped", greeting.consume(t + 82_000, true));
        greeting.observeStatus(-1, t + 100_000);
        assertFalse(greeting.consume(t + 101_000, true));
    }

    @Test public void wakeWordIsTheExactWordOnly() {
        assertTrue(Policies.isWakeWord("Астор"));
        assertTrue(Policies.isWakeWord("astor!"));
        assertTrue(Policies.isWakeWord("«АСТОР»"));
        assertFalse(Policies.isWakeWord("Астория"));
        assertFalse(Policies.isWakeWord("Астора"));
        assertFalse(Policies.isWakeWord("скажи Астор"));
        assertFalse(Policies.isWakeWord(42));
        assertFalse(Policies.isWakeWord(null));
    }

    @Test public void questionEndsOnSilenceAfterSpeechOrOnTheLimits() {
        VoiceActivity silence = new VoiceActivity();
        assertFalse(silence.shouldFinishAt(5, -80));
        assertTrue(silence.shouldFinishAt(8, -80));

        VoiceActivity speech = new VoiceActivity();
        assertFalse(speech.shouldFinishAt(0.2, -20));
        assertFalse(speech.shouldFinishAt(1, -80));
        assertTrue(speech.shouldFinishAt(2.1, -80));
        assertTrue(new VoiceActivity().shouldFinishAt(30, Double.NaN));

        assertEquals(0, VoiceActivity.decibels(32767), 0.001);
        assertTrue("a quiet room", VoiceActivity.decibels(300) < VoiceActivity.SPEECH_DB);
        assertTrue(VoiceActivity.decibels(4000) > VoiceActivity.SPEECH_DB);
        assertTrue(Double.isInfinite(VoiceActivity.decibels(0)));
    }

    @Test public void ownRecordingIsNotACallButARealCallAlwaysInterrupts() {
        assertFalse("headset channel used by our recorder", Policies.callShouldInterrupt(false, false, true, true));
        assertTrue(Policies.callShouldInterrupt(true, false, true, true));
        assertTrue(Policies.callShouldInterrupt(true, false, false, true));
        assertTrue(Policies.callShouldInterrupt(false, true, false, true));
        assertTrue(Policies.callShouldInterrupt(false, false, true, false));
        assertFalse(Policies.callShouldInterrupt(false, false, false, true));
        assertFalse(Policies.callShouldInterrupt(false, false, false, false));
    }

    private static Map<Integer, Integer> map(int... pairs) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    @Test public void gestureProfileTouchesOnlyReportedDoubleAndTriplePresses() {
        assertTrue("empty mappings never lead to writes", Policies.gestureProfile(Collections.emptyMap()).isEmpty());
        Map<Integer, Integer> reported = map(1, 10, 2, 10, 7, 0, 8, 0, 18, 3, 19, 4, 24, 10, 25, 12, 5, 1, 34, 5, 35, 6);
        Map<Integer, Integer> profile = Policies.gestureProfile(reported);
        assertEquals(map(18, 4, 19, 7, 5, 3), profile);
        for (int kept : new int[]{1, 2, 7, 8, 34, 35}) assertFalse(profile.containsKey(kept));
        assertEquals(map(25, 7), Policies.gestureProfile(map(25, 12)));

        Map<String, Integer> backup = Policies.gestureBackup(Collections.emptyMap(), reported, profile);
        assertEquals(Map.of("18", 3, "19", 4, "5", 1), backup);
        Map<String, Integer> second = Policies.gestureBackup(backup, map(19, 7, 3, 10), map(19, 10, 3, 4, 99, 7));
        assertEquals("repeated edits preserve originals", Integer.valueOf(4), second.get("19"));
        assertEquals(Integer.valueOf(10), second.get("3"));
        assertFalse("unknown gestures are never backed up", second.containsKey("99"));
    }
}
