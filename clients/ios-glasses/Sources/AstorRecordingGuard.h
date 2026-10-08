#import <Foundation/Foundation.h>

/*
 A recording that has just started is not ended by the next event that arrives.

 The glasses and iOS both report the same tap more than once: the SDK's chat-session Initiate is followed
 by a Terminate while it sets up its own channel, and a media command can land right behind it. Without
 a guard the recorder started and stopped within a few hundred milliseconds, and the staff member was
 left with a cue and no question. The rule here: for `AstorRecordingGuardSeconds` after the start, a
 stop that comes from an event is ignored; a stop from an explicit cancel, a call or the 30-second limit
 is not an event and is never held back.
 */
static const NSTimeInterval AstorRecordingGuardSeconds = 1.2;

/** YES when an event-driven stop arriving `now` for a recording started at `startedAt` must be ignored. */
static inline BOOL AstorRecordingStopIsTooEarly(NSDate *startedAt, NSDate *now) {
    if(!startedAt || !now)return NO;
    return [now timeIntervalSinceDate:startedAt] < AstorRecordingGuardSeconds;
}

/** YES when a second start event arriving `now` is the same tap repeated, not a new request. */
static inline BOOL AstorRecordingStartIsRepeated(NSDate *startedAt, NSDate *now) {
    return AstorRecordingStopIsTooEarly(startedAt, now);
}
