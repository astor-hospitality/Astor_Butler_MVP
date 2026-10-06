#import <Foundation/Foundation.h>
#import "../Sources/AstorCallPolicy.h"
static void check(BOOL condition,NSString *message){if(!condition){NSLog(@"FAIL: %@",message);exit(1);}}
int main(void){@autoreleasepool {
    check(!AstorCallShouldInterrupt(NO,NO,YES,YES),@"SDK InCall caused by our SCO recording does not erase the question");
    check(AstorCallShouldInterrupt(YES,NO,YES,YES),@"A real iPhone call still stops our own HFP recorder");
    check(AstorCallShouldInterrupt(YES,NO,NO,YES),@"CallKit wins even when the accessory has not reported the call yet");
    check(AstorCallShouldInterrupt(NO,YES,NO,YES),@"An incoming/three-way ringing event interrupts even during wake-word listening");
    check(AstorCallShouldInterrupt(NO,NO,YES,NO),@"Accessory InCall outside our audio is still protected");
    check(!AstorCallShouldInterrupt(NO,NO,NO,YES),@"SDK NotInCall after our capture is not a new call transition");
    check(!AstorCallShouldInterrupt(NO,NO,NO,NO),@"Idle/unknown/AI chat hints do not invent a telephone call");
    puts("Call policy: own SCO recording, actual CallKit and ringing checks passed");return 0;
}}
