package com.astor.glasses.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small decisions shared with the iPhone client, kept free of any device or platform call. */
public final class Policies {
    private Policies() { }

    /** The wake word is the whole recognised word, so "Астория" or "скажи Астор" do not start a question. */
    public static boolean isWakeWord(Object word) {
        if (!(word instanceof String)) return false;
        String clean = ((String) word).toLowerCase(Locale.ROOT).replaceAll("^\\p{P}+|\\p{P}+$", "");
        return clean.equals("астор") || clean.equals("astor");
    }

    /**
     * Our own recorder uses the headset call channel, and the glasses may report that as a call.
     * A real phone call and ringing still interrupt at once, including during our own audio.
     */
    public static boolean callShouldInterrupt(boolean systemCall, boolean ringing, boolean sdkInCall, boolean ownHeadsetAudio) {
        return systemCall || ringing || (sdkInCall && !ownHeadsetAudio);
    }

    /**
     * The staff gesture profile: only double and triple presses that the glasses actually report are
     * reassigned; power, single camera presses and volume swipes are left alone.
     * Keys are the SDK's operation codes, values its function codes.
     */
    public static Map<Integer, Integer> gestureProfile(Map<Integer, Integer> reported) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        assignFirst(result, reported, new int[]{19, 25, 4, 31}, 7);
        assignFirst(result, reported, new int[]{18, 24, 3, 30}, 4);
        assignFirst(result, reported, new int[]{5, 6}, 3);
        return result;
    }

    private static void assignFirst(Map<Integer, Integer> result, Map<Integer, Integer> reported, int[] operations, int function) {
        for (int operation : operations) {
            if (reported.containsKey(operation)) {
                result.put(operation, function);
                return;
            }
        }
    }

    /** Original values of the gestures about to change. A value saved once is never overwritten by a later edit. */
    public static Map<String, Integer> gestureBackup(Map<String, Integer> existing, Map<Integer, Integer> reported,
                                                     Map<Integer, Integer> changes) {
        Map<String, Integer> saved = new HashMap<>(existing);
        for (Integer operation : changes.keySet()) {
            String key = String.valueOf(operation);
            if (reported.containsKey(operation) && !saved.containsKey(key)) saved.put(key, reported.get(operation));
        }
        return saved;
    }
}
