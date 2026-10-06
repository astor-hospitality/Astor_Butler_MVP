package com.astor.glasses.core;

/**
 * When to stop recording a question: 1.8 seconds of silence after speech, 8 seconds without any
 * speech, or 30 seconds in total. Works on loudness alone and never looks at what was said.
 */
public final class VoiceActivity {
    public static final double MAX_SECONDS = 30, SPEECH_DB = -35;

    private boolean heardSpeech;
    private double lastSpeech;

    public boolean heardSpeech() { return heardSpeech; }

    /** @param power loudness in decibels relative to full scale; NaN or infinity counts as silence */
    public boolean shouldFinishAt(double elapsedSeconds, double power) {
        if (!Double.isNaN(power) && !Double.isInfinite(power) && power > SPEECH_DB) {
            heardSpeech = true;
            lastSpeech = elapsedSeconds;
        }
        return elapsedSeconds >= MAX_SECONDS
                || (heardSpeech && elapsedSeconds >= 2 && elapsedSeconds - lastSpeech >= 1.8)
                || (!heardSpeech && elapsedSeconds >= 8);
    }

    /** Loudness of 16-bit samples as decibels relative to full scale. */
    public static double decibels(int peakAmplitude) {
        return peakAmplitude <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(peakAmplitude / 32767.0);
    }
}
