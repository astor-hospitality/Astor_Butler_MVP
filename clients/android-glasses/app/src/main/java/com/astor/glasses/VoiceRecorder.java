package com.astor.glasses;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.astor.glasses.core.Assist;
import com.astor.glasses.core.VoiceActivity;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

/**
 * Records one question through the headset microphone as AAC, 16 kHz mono, at most 30 seconds: the
 * format the backend accepts. For Android the glasses are a Bluetooth headset, so no vendor SDK is
 * needed for this part. The temporary file is deleted as soon as it is read.
 */
final class VoiceRecorder {

    interface Listener {
        /** aac is null when nothing usable was recorded. */
        void finished(byte[] aac);
    }

    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File file;
    private VoiceActivity activity;
    private long startedAt;
    private Listener listener;

    VoiceRecorder(Context context) {
        this.context = context.getApplicationContext();
        this.audio = context.getSystemService(AudioManager.class);
    }

    boolean recording() { return recorder != null; }

    /** The Bluetooth headset to record from: the glasses when they are connected, else any headset, else null. */
    AudioDeviceInfo headset() {
        AudioDeviceInfo any = null;
        for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
            if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
            if (isGlasses(device)) return device;
            if (any == null) any = device;
        }
        return any;
    }

    /** The same name check as the iPhone client uses for its audio route. */
    static boolean isGlasses(AudioDeviceInfo device) {
        String name = String.valueOf(device.getProductName()).toLowerCase(Locale.ROOT);
        return name.contains("ai glasses") || name.contains("563b");
    }

    /**
     * @param device the headset from {@link #headset()}, or null to record from the phone itself
     * @return false when the microphone could not be started
     */
    boolean start(AudioDeviceInfo device, Listener listener) {
        if (recorder != null) return false;
        try {
            if (device != null) {
                audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (!audio.setCommunicationDevice(device)) throw new IllegalStateException();
            }
            File dir = new File(context.getCacheDir(), "voice");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException();
            file = new File(dir, UUID.randomUUID() + ".m4a");
            recorder = new MediaRecorder(context);
            recorder.setAudioSource(device != null ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            release();
            return false;
        }
        this.listener = listener;
        activity = new VoiceActivity();
        startedAt = System.nanoTime();
        main.postDelayed(this::meter, 100);
        return true;
    }

    // Without the glasses SDK there is no "press to finish" gesture, so the question ends on silence.
    private void meter() {
        if (recorder == null) return;
        double elapsed = (System.nanoTime() - startedAt) / 1e9;
        if (activity.shouldFinishAt(elapsed, VoiceActivity.decibels(recorder.getMaxAmplitude()))) finish();
        else main.postDelayed(this::meter, 100);
    }

    /** Stops and hands the recording over. Safe to call when nothing is being recorded. */
    void finish() {
        if (recorder == null) return;
        byte[] aac = null;
        try {
            recorder.stop();
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > 0 && bytes.length < Assist.MAX_MEDIA_BYTES) aac = bytes;
        } catch (Exception e) {
            // stop() throws when nothing was captured; that is "no recording", not a crash.
        }
        Listener done = listener;
        release();
        if (done != null) done.finished(aac);
    }

    /** Stops without delivering anything: a call came in, or the screen is going away. */
    void cancel() {
        release();
    }

    private void release() {
        main.removeCallbacksAndMessages(null);
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
        if (file != null) {
            file.delete();
            file = null;
        }
        listener = null;
        audio.clearCommunicationDevice();
        audio.setMode(AudioManager.MODE_NORMAL);
    }
}
