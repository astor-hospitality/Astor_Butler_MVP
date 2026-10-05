# Astor Glass iPhone companion

Updated 2026-10-05 against Butler main a4023a7. Source snapshot of the signed iPhone training companion. The phone connects the AIBuds glasses SDK to the isolated HTTPS pilot API. Staff task transport/auth/persistence being developed separately are not connected here.

## Build

Use macOS, Xcode 26+, CocoaPods and XcodeGen. Obtain the licensed AIBuds-SDK-iOS checkout separately; SDK, Pods and build output are excluded.

    export ASTOR_AIBUDS_SDK_PATH=/absolute/path/to/AIBuds-SDK-iOS
    xcodegen generate
    pod install
    xcodebuild -workspace AstorGlassesProbe.xcworkspace -scheme AstorGlassesProbe \
      -configuration Debug -destination 'id=YOUR_DEVICE_UDID' \
      CODE_SIGN_ENTITLEMENTS='' build

Set your signing team in project.yml. Personal Team Debug builds omit the Hotspot entitlement. Wi-Fi import/charging archive is separately owned and unfinished files are excluded here. No provider/SSH/S3/mobile credentials belong in sources or bundle.

## Table photos

Explicitly start training for two guests. Stages: TABLE_PREPARE, PLACE_SETTINGS, WATER_MENU, FINAL_CHECK. The second and fourth require a matching archived photo receipt before manual advance. Other photos are optional. The SDK takes one JPEG; no continuous camera feed.

The context carries session UUID, BUSINESS_LUNCH_TWO, stage and revision. Server guidance and informational vision produce a private JPEG + reply.json with that context. Only matching request UUID/context and archived:true enable the local checkpoint. Delayed response, changed step, cancellation and missing receipt cannot advance it. No restaurant task/table identifiers are invented.

The compressed JPEG stays only in memory. Retry keeps the same bytes, UUID and context for at most 110 seconds; server successful-reply cache lasts 120 seconds. Starting a session, advancing, cancellation and expiry clear pending media. The receipt confirms storage, not image accuracy or a real assignment's completion.

## Greeting and “Астор”

Greeting listens only to SDK didWearStatusChanged when the model reports supported, enabled wear detection. Reconnection is not a wear event. Duplicate events, 30-second cooldown, busy audio/call state and 10-second pending expiry guard playback. UI may enable a configurable wear detector. Actual model support still needs a device test.

The explicit “Слушать «Астор»” mode uses glasses Bluetooth HFP and Apple Speech. It checks supportsOnDeviceRecognition and requires local recognition. Unavailable local Russian speech, missing permissions or missing glasses input leave it off. Standby audio/transcripts are not logged, saved or sent to the server; there is no cloud fallback.

An exact Астор/Astor segment stops standby and starts the existing bounded AAC question recorder. Wait for its cue, then ask. A silence heuristic stops after 1.8 seconds following detected speech, 8 seconds without speech, or a 30-second hard limit. Only that question goes to backend STT/assist. Restaurant-noise testing remains open.

Standby pauses for questions, replies, photos, music and calls. Stop/disconnect/interruption turn it off. Recognizer sessions rotate every 50 seconds with a short restart gap. Locked-phone continuous operation and long-running reliability are not accepted yet. HFP microphone selection does not identify the speaker; voice-owner verification is not implemented.

## Checks

    clang -fobjc-arc -framework Foundation tests/lunch-guide.m Sources/AstorLunchGuide.m -o /tmp/lunch-test
    /tmp/lunch-test
    clang -fobjc-arc -framework Foundation tests/wear-greeting.m Sources/AstorWearGreeting.m -o /tmp/wear-test
    /tmp/wear-test
    clang -fobjc-arc -framework Foundation tests/wake-policy.m Sources/AstorVoiceActivity.m -o /tmp/wake-test
    /tmp/wake-test

Build/sign/install, camera/audio, server media, wake recognition and wear events are separate acceptance results.
