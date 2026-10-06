package com.astor.glasses.core;

import java.util.Map;

/**
 * What the application needs from the glasses beyond being a Bluetooth headset.
 *
 * The iPhone client gets all of this from the vendor's AIBuds SDK. No Android build of that SDK is in
 * this repository, so nothing implements this interface yet: the application works with the glasses
 * as a headset only (microphone and speaker) and says so. An adapter over the Android SDK goes here.
 */
public interface GlassesDevice {

    interface Listener {
        /** One JPEG taken on request. There is no continuous camera feed. */
        void onPhoto(byte[] jpeg);

        /** unknown -1, off 0, worn 1..3, as in {@link WearGreeting}. */
        void onWearStatus(int status);

        /** component: glasses 0, charging case 3. Only the glasses charging is a trigger for the archive. */
        void onCharging(int component, boolean charging);

        /** Operation code to function code, as in {@link Policies#gestureProfile}. */
        void onGestures(Map<Integer, Integer> mapping);

        void onCallStatus(boolean inCall, boolean ringing);

        void onDisconnected();
    }

    boolean ready();

    void setListener(Listener listener);

    /** Asks the glasses for one photo; the result arrives in {@link Listener#onPhoto}. False when refused. */
    boolean requestPhoto();

    void assignGestures(Map<Integer, Integer> changes);
}
