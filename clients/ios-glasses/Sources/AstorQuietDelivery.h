#import <Foundation/Foundation.h>

/*
 A message addressed to the staff member waits until they are not talking.

 The decision is this object's only job: it holds no audio, reads no microphone and sends nothing.
 The caller feeds it what it knows — our own audio and call state, and (when the microphone is
 available) the fact that speech was just heard nearby — and asks whether the next message may be
 spoken now. After speech or any of our own audio ends, delivery waits `AstorQuietDeliveryDelay`
 seconds of quiet, so a message never lands in the middle of a sentence.

 A message is informational. Playing it is not an acknowledgement, and this object never reports
 anything back to the server.
 */
static const NSTimeInterval AstorQuietDeliveryDelay = 3.0;

@interface AstorQuietDeliveryMessage : NSObject
@property(nonatomic,readonly) NSString *messageId;   // canonical lowercase UUID from the server
@property(nonatomic,readonly) NSString *text;        // what the staff member hears, trimmed
@property(nonatomic,readonly) NSDate *receivedAt;
+ (instancetype)withId:(NSString *)messageId text:(NSString *)text at:(NSDate *)date;
@end

/** What the phone knows about the moment, apart from the messages themselves. */
typedef struct {
    BOOL speechNearby;       // microphone level above the speaking threshold within the last sample
    BOOL ownAudioActive;     // our question recorder, cue, answer playback or TTS is running
    BOOL callActive;         // CallKit call, ringing, or the accessory reports a call
    BOOL musicActive;
    BOOL glassesReady;       // connected and our audio goes to the glasses
} AstorQuietDeliveryState;

@interface AstorQuietDelivery : NSObject
/** Oldest first; a message already queued or already spoken is ignored. At most 20 wait. */
- (BOOL)enqueue:(AstorQuietDeliveryMessage *)message;
@property(nonatomic,readonly) NSUInteger waiting;
@property(nonatomic,readonly) NSString *status;
/** Feeds the moment and returns the message to speak now, or nil while the staff member is busy. */
- (AstorQuietDeliveryMessage *)nextAt:(NSDate *)date state:(AstorQuietDeliveryState)state;
/** Called once playback of that message actually started. */
- (void)startedSpeaking:(AstorQuietDeliveryMessage *)message at:(NSDate *)date;
/** Playback finished or was interrupted; an interrupted message goes back to the front of the queue. */
- (void)finishedSpeaking:(AstorQuietDeliveryMessage *)message at:(NSDate *)date delivered:(BOOL)delivered;
- (void)reset;
@end
