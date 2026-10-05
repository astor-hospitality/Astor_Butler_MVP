# Astor Glass Android companion

2026-10-05, first build. Android counterpart of the iPhone companion (`clients/ios-glasses`, PR #13). It talks to the same pilot API, `/api/glasses/capabilities` and `/api/glasses/assist`.

## Where it stands

The iPhone client reaches the glasses through the vendor's licensed AIBuds SDK. No Android build of that SDK is in this repository and none was found publicly, so on Android the glasses are used the only way that needs no SDK: as a Bluetooth headset, microphone and speaker.

| Works now | Waits for the vendor's Android SDK |
| --- | --- |
| Text question and answer | Photo from the camera of the glasses |
| Voice question through the headset microphone, AAC 16 kHz mono up to 30 s | Gestures and the staff gesture profile |
| Answer read aloud by the phone, or the server's audio when it sends one | Wear detection and the greeting |
| Training lunch with the same photo rules as on the iPhone | Battery and charging events, session archive on charging |
| Access token encrypted with an Android Keystore key | Wi-Fi import of files stored on the glasses |

Not ported and not blocked by the SDK: the wake word «Астор» (needs on-device Russian speech recognition on Android), assistant shortcuts, the upload queue for `/api/glasses/media`.

The seam for the SDK is `core/.../GlassesDevice.java`: an adapter over the Android SDK implements it, the rules behind it are already here and tested.

## Layout

```text
core/   plain Java, no Android: rules and the server contract, tested on any computer
        LunchGuide      training steps, photo context, receipt check      (AstorLunchGuide)
        Assist          request body, reply check, endpoint check         (sendText, AstorAssistReply)
        Policies        wake word, call interruption, gesture profile     (AstorWakeWord, AstorCallPolicy, AstorGesturePolicy)
        WearGreeting    when a wear event deserves a greeting             (AstorWearGreeting)
        VoiceActivity   when a spoken question is over                    (AstorVoiceActivity)
        GlassesDevice   what the vendor SDK has to provide
app/    the Android application: one screen, request flow, recorder, token store
```

`core` is compiled and tested against Android's own `org.json`, not the newer library of the same name. The first emulator run crashed on a method that exists only in the newer one; now such a call does not compile.

## Differences from the iPhone client

- A training photo comes from the phone's camera, as a stand-in until the SDK gives access to the glasses. It is resized to 1280 px, sent, and deleted; it never reaches the gallery.
- A spoken question ends by itself: 1.8 s of silence after speech, 8 s without speech, 30 s at most. On the iPhone a press on the glasses ends it; without the SDK there is no such press.
- Any Bluetooth headset is accepted for the microphone, the glasses are preferred when present. The iPhone accepts only the glasses.
- Debug builds only: plain http to the developer's own computer (`10.0.2.2`, `localhost`) and the phone's own microphone, so the client can be tried on an emulator against a stand-in server. Release builds accept https only and refuse to record without a headset.

## Build and test

JDK 17 or newer and the Android SDK (platform 36). Put the SDK path into `local.properties` as `sdk.dir=...` or set `ANDROID_HOME`.

    ./gradlew :core:test            # 13 tests, no phone or SDK needed
    ./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk

No backend address, token or key is in the sources. The address and the token are typed on the screen.

## Checked

- `core`: 13 tests. They repeat the iPhone client's checks for the lunch guide, the greeting, the wake word, voice activity, calls and gestures, and add the request and reply contract.
- Debug build on an Android 16 emulator against a local stand-in server that follows the contract:
  - text question; a reply to another request and a blank answer are both rejected and the previous answer stays;
  - training from start to finish: steps 2 and 4 stay closed without a photo, a failed upload can be re-sent with the same request id and the same bytes, the step opens only on a matching stored receipt;
  - voice question: permission, recording, automatic and manual stop.
- Two recordings made by the application pass the backend's own validator, `scripts/glasses_stt.py --validate-only`: AAC, 16 kHz, mono, declared duration matches the samples.
- After use the private cache holds no recording or photo, and the token is stored only in encrypted form.

## Not checked

- Anything with real glasses or a real phone: Bluetooth microphone, answer in the glasses' speaker, calls.
- The real backend: no token for it was used. The stand-in follows the documented contract, it is not the server.
- Release build and signing.
