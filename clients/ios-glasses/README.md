# Astor Glass iPhone companion

Updated 2026-10-07 against Butler main. Source of the working app for a staff member on shift: the phone connects the AIBuds glasses SDK to the isolated HTTPS pilot API. Staff task transport/auth/persistence being developed separately are not connected here, so nothing in this app acknowledges an assignment.

The demo for a named guest, the "Астор" wake word (with its offline decoder and project variant) and Wi-Fi import of the glasses' own memory were removed on 2026-10-07: the wake word has no local Russian recognition on this iPhone and Wi-Fi import needs a HotspotConfiguration entitlement this build cannot have. History is in [the R&D handoff](../../docs/operations/GLASSES_RD_HANDOFF.md). The double tap now always starts and ends a question; the back tap repeats the current step or the last answer.

## Build

Use macOS, Xcode 26+, CocoaPods and XcodeGen. Obtain the licensed AIBuds-SDK-iOS checkout separately; SDK, Pods and build output are excluded.

    export ASTOR_AIBUDS_SDK_PATH=/absolute/path/to/AIBuds-SDK-iOS
    xcodegen generate
    pod install
    xcodebuild -workspace AstorGlassesProbe.xcworkspace -scheme AstorGlassesProbe \
      -configuration Debug -destination 'id=YOUR_DEVICE_UDID' \
      CODE_SIGN_ENTITLEMENTS='' build

Set your signing team in project.yml. Personal Team Debug builds omit the Hotspot entitlement, so SDK Wi-Fi import remains disabled. The integrated phoneCapture archive works through files already received on the phone. No provider/SSH/S3/mobile credentials belong in sources or bundle.

## Table photos

Explicitly start the lunch for two guests. Stages: TABLE_PREPARE, PLACE_SETTINGS, WATER_MENU, FINAL_CHECK. The second and fourth require a matching archived photo receipt before manual advance. Other photos are optional. The SDK takes one JPEG; no continuous camera feed.

The context carries session UUID, BUSINESS_LUNCH_TWO, stage and revision, and now rides along with a spoken or typed question too, so the shift report can show it. Server guidance and informational vision produce a private JPEG + reply.json with that context. Only matching request UUID/context and archived:true enable the local checkpoint. Delayed response, changed step, cancellation and missing receipt cannot advance it. No restaurant task/table identifiers are invented.

The analysis retry JPEG stays in memory. Retry keeps the same bytes, UUID and context for at most 110 seconds; server successful-reply cache lasts 120 seconds. Starting a session, advancing, cancellation and expiry clear pending analysis media. An explicitly opened archive session also retains a private file copy until its own server receipt. The receipt confirms storage, not image accuracy or a real assignment's completion.

## Archive on charging

Select “Подготовить архив сессии” before capture. The prepared JPEG (1280 px, compressed for analysis) and finished AAC question are copied to a private durable phone queue; standby microphone buffers are excluded. A live Glass charging event or “Выгрузить сейчас” moves that session's held files to pending uploads. Case charging alone is not a trigger. The queue requires mediaArchive.enabled from the backend and checks file/session UUID, standard SHA-256, size and archived:true before accepting each receipt. Interrupted uploads retain the file for retry.

The separate /api/glasses/media archive stores JPEG/audio MP4/video MP4 up to 64 MiB. It does not run analysis or satisfy a training checkpoint. Saved video import from glasses memory requires Wi-Fi and a signing team with HotspotConfiguration; this Personal Team build does not enable it. See [DOCK_ARCHIVE.md](docs/DOCK_ARCHIVE.md) for the queue contract and separate physical acceptance results.

## Greeting when worn

Greeting listens only to SDK didWearStatusChanged when the model reports supported, enabled wear detection. Reconnection is not a wear event. Duplicate events, 30-second cooldown, busy audio/call state and 10-second pending expiry guard playback. UI may enable a configurable wear detector. Actual model support still needs a device test.

## Messages to the staff member

A message addressed to the staff member is handled by where their attention is.

**App on screen.** The message is shown as a line in the app and as a local notification, once per
message, and never read aloud: the person is looking at the phone, so Astor does not talk over them.
The microphone stays closed.

**Locked or in the background.** The glasses are the only way to reach them, so the message is read
aloud — but only in a pause. `AstorQuietDelivery` holds the queue and the decision; `main.m` feeds it
our own audio and call state and, while something waits, the microphone's loudness. Speech nearby, our
own audio, a call or music hold the message back; after all of that stops, delivery waits three quiet
seconds, so a message never lands mid-sentence. An interrupted message returns to the front of the
queue; a repeated id is never spoken twice; at most 20 wait.

The microphone opens only while a message waits for a pause and closes as soon as the queue is empty
or the message has been spoken. It measures loudness only: nothing is recognized, recorded, kept or
sent. The -38 dBFS speaking threshold is a starting value to check in a noisy room.

Astor reads the message in the server's own voice (`POST /api/glasses/speech`, male, MP3) when the
server has one, and in the phone's voice otherwise.

Messages come from `GET /api/glasses/messages` beside the assist endpoint, polled no more often than
every 20 seconds with the same bearer, at most 20 per answer and 600 characters each. A missing
endpoint (404) is logged once and the queue stays empty. Playing or showing a message acknowledges
nothing and sends nothing back, so an unanswered message stays unanswered for the restaurant.

## Answering with your own voice

After a message has been read aloud, Astor offers an answer: a double tap within 25 seconds starts the
usual bounded recorder, and that recording goes to `POST /api/glasses/transcribe` — recognition only,
no model call. The text becomes a draft in `AstorReplyDrafts`, shown in the app with its question.

Nothing is sent from here. "Отправить ответ в Telegram" opens Telegram with the text prefilled
(`tg://msg?text=…`); the staff member picks the chat and taps send in their own account. Opening
Telegram does not remove the draft — they may still change their mind there — so it stays, marked as
opened but unconfirmed, until "Отправил · убрать черновик". A draft is replaced if the same message is
answered again, expires after twelve hours, and twenty are kept at most. A draft nobody sent simply
expires, and the restaurant still sees the message as unanswered.

Before the audio of a message is played, the room is checked again: the server may have taken a second
to synthesize, and a conversation, a call or the phone coming into the staff member's hands in that
time sends the message back to the queue instead of into the middle of a sentence. A message counts as
read only if it actually reached the output.

## Before a show

A recording that has just started is not ended by the next event: the SDK reports a tap more than once
(an Initiate followed by its own Terminate, and a media command behind it), so for 1.2 seconds after the
recorder starts an event-driven stop is ignored. A cancel, a call or the 30-second limit are not events
and are never held back. The greeting names whoever is set under "Кого приветствовать" on the glasses
tab; a failed request is explained in one sentence — no network, a wrong or expired token, a busy or
unavailable server — rather than as an HTTP code. The device checklist is `docs/PHYSICAL_TEST_CHECKLIST.md`.

## Checks

    clang -fobjc-arc -framework Foundation tests/lunch-guide.m Sources/AstorLunchGuide.m -o /tmp/lunch-test
    /tmp/lunch-test
    clang -fobjc-arc -framework Foundation tests/wear-greeting.m Sources/AstorWearGreeting.m -o /tmp/wear-test
    /tmp/wear-test
    clang -fobjc-arc -framework Foundation tests/quiet-delivery.m Sources/AstorQuietDelivery.m -o /tmp/quiet-test
    /tmp/quiet-test
    clang -fobjc-arc -framework Foundation tests/reply-drafts.m Sources/AstorReplyDrafts.m -o /tmp/drafts-test
    /tmp/drafts-test
    clang -fobjc-arc -framework Foundation tests/recording-guard.m -o /tmp/guard-test
    /tmp/guard-test

Build/sign/install, camera/audio, server media, quiet message delivery in a real room, the reply draft on a locked phone and wear events are separate acceptance results.
