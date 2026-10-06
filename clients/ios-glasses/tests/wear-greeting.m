#import <Foundation/Foundation.h>
#import "../Sources/AstorWearGreeting.h"
static void check(BOOL value){if(!value){puts("FAIL greeting policy");exit(1);}}
int main(void){@autoreleasepool{
    NSDate *now=[NSDate dateWithTimeIntervalSince1970:1000];
    AstorWearGreeting *p=[AstorWearGreeting new];
    check(![p consumeAt:now allowed:YES]);
    [p observeStatus:0 at:now];check(![p consumeAt:now allowed:YES]);
    [p observeStatus:3 at:now];check(![p consumeAt:now allowed:NO]);
    check([p consumeAt:[now dateByAddingTimeInterval:1] allowed:YES]);
    [p observeStatus:1 at:[now dateByAddingTimeInterval:2]];check(![p consumeAt:now allowed:YES]);
    [p observeStatus:0 at:[now dateByAddingTimeInterval:3]];
    [p observeStatus:3 at:[now dateByAddingTimeInterval:4]];check(![p consumeAt:now allowed:YES]);
    [p observeStatus:0 at:[now dateByAddingTimeInterval:31]];
    [p observeStatus:3 at:[now dateByAddingTimeInterval:32]];check([p consumeAt:[now dateByAddingTimeInterval:32] allowed:YES]);
    [p observeStatus:0 at:[now dateByAddingTimeInterval:70]];
    [p observeStatus:3 at:[now dateByAddingTimeInterval:71]];check(![p consumeAt:[now dateByAddingTimeInterval:82] allowed:YES]);
    [p observeStatus:-1 at:[now dateByAddingTimeInterval:100]];check(![p consumeAt:[now dateByAddingTimeInterval:101] allowed:YES]);
    puts("Wear greeting policy: transition, duplicate, cooldown, busy and expiry checks passed");return 0;
}}
