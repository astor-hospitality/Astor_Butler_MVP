#import "../Sources/AstorQuietDelivery.h"
#import <assert.h>

static AstorQuietDeliveryState state(BOOL speech, BOOL own, BOOL call) {
    AstorQuietDeliveryState s = {0};
    s.speechNearby=speech; s.ownAudioActive=own; s.callActive=call; s.glassesReady=YES;
    return s;
}

int main(void) { @autoreleasepool {
    NSDate *t0=[NSDate dateWithTimeIntervalSince1970:1760000000];
    NSDate *(^at)(NSTimeInterval) = ^(NSTimeInterval s){ return [t0 dateByAddingTimeInterval:s]; };
    NSString *one=@"80d26cf1-5139-4121-a4ca-dfb14aac225c", *two=@"ff5a8c58-bb60-43f4-b542-1e26c8b96581";

    // A malformed message is refused rather than spoken.
    assert([AstorQuietDeliveryMessage withId:@"nope" text:@"текст" at:t0]==nil);
    assert([AstorQuietDeliveryMessage withId:one text:@"   " at:t0]==nil);
    assert([AstorQuietDeliveryMessage withId:one text:[@"x" stringByPaddingToLength:601 withString:@"x" startingAtIndex:0] at:t0]==nil);

    AstorQuietDelivery *delivery=[AstorQuietDelivery new];
    AstorQuietDeliveryMessage *first=[AstorQuietDeliveryMessage withId:one text:@"  Стол пять ждёт счёт.  " at:t0];
    assert([first.text isEqual:@"Стол пять ждёт счёт."]);
    assert([delivery enqueue:first]);
    assert(![delivery enqueue:[AstorQuietDeliveryMessage withId:one text:@"тот же id" at:t0]]);
    assert(delivery.waiting==1);

    // While the staff member is talking, the message waits and the quiet timer does not run.
    assert([delivery nextAt:at(0) state:state(YES,NO,NO)]==nil);
    assert([delivery.status containsString:@"Отложено"]);
    assert([delivery nextAt:at(10) state:state(YES,NO,NO)]==nil);

    // Three seconds of quiet after the speech, not three seconds of conversation.
    assert([delivery nextAt:at(11) state:state(NO,NO,NO)]==nil);
    assert([delivery nextAt:at(13.4) state:state(NO,NO,NO)]==nil);
    assert([delivery nextAt:at(14.1) state:state(NO,NO,NO)]==first);

    // Our own audio and a call hold it back the same way, and restart the wait.
    assert([delivery nextAt:at(14.2) state:state(NO,YES,NO)]==nil);
    assert([delivery nextAt:at(14.3) state:state(NO,NO,YES)]==nil);
    assert([delivery.status containsString:@"звонка"]);
    assert([delivery nextAt:at(15) state:state(NO,NO,NO)]==nil);
    assert([delivery nextAt:at(18.1) state:state(NO,NO,NO)]==first);

    // Without glasses audio nothing is spoken into the phone's own speaker.
    AstorQuietDeliveryState noGlasses=state(NO,NO,NO); noGlasses.glassesReady=NO;
    assert([delivery nextAt:at(18.2) state:noGlasses]==nil);
    assert([delivery.status containsString:@"очков"]);

    // Playing it takes it out of the queue; nothing else is offered meanwhile.
    [delivery startedSpeaking:first at:at(19)];
    assert(delivery.waiting==0);
    assert([delivery enqueue:[AstorQuietDeliveryMessage withId:two text:@"Второе сообщение." at:at(19)]]);
    assert([delivery nextAt:at(30) state:state(NO,NO,NO)]==nil);

    // An interrupted message returns to the front and waits for the next pause.
    [delivery finishedSpeaking:first at:at(20) delivered:NO];
    assert(delivery.waiting==2);
    assert([delivery nextAt:at(23.5) state:state(NO,NO,NO)]==first);
    [delivery startedSpeaking:first at:at(24)];
    [delivery finishedSpeaking:first at:at(25) delivered:YES];
    assert(delivery.waiting==1);
    assert([delivery nextAt:at(28.1) state:state(NO,NO,NO)].text!=nil);
    assert([[delivery nextAt:at(28.1) state:state(NO,NO,NO)].messageId isEqual:two]);

    // A delivered message is never repeated, even if the server sends it again.
    assert(![delivery enqueue:[AstorQuietDeliveryMessage withId:one text:@"Стол пять ждёт счёт." at:at(29)]]);

    // The queue is bounded; reset clears what is waiting.
    AstorQuietDelivery *bounded=[AstorQuietDelivery new];
    for(int i=0;i<25;i++) [bounded enqueue:[AstorQuietDeliveryMessage withId:[NSUUID UUID].UUIDString text:@"сообщение" at:t0]];
    assert(bounded.waiting==20);
    [bounded reset];
    assert(bounded.waiting==0);
    assert([bounded.status isEqual:@"Сообщений нет"]);

    printf("quiet-delivery: OK\n");
    return 0;
} }
