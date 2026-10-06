#import <Foundation/Foundation.h>
#import "../Sources/AstorWakeWord.h"
#import "../Sources/AstorVoiceActivity.h"
static void check(BOOL value){if(!value){puts("FAIL wake policy");exit(1);}}
int main(void){@autoreleasepool{
    check(AstorIsWakeWord(@"Астор") && AstorIsWakeWord(@"astor!") && AstorIsWakeWord(@"«АСТОР»"));
    check(!AstorIsWakeWord(@"Астория") && !AstorIsWakeWord(@"Астора") && !AstorIsWakeWord(@"скажи Астор") && !AstorIsWakeWord(@42));
    AstorVoiceActivity *silence=[AstorVoiceActivity new];
    check(![silence shouldFinishAt:5 power:-80] && [silence shouldFinishAt:8 power:-80]);
    AstorVoiceActivity *speech=[AstorVoiceActivity new];
    check(![speech shouldFinishAt:.2 power:-20]);
    check(![speech shouldFinishAt:1 power:-80]);
    check([speech shouldFinishAt:2.1 power:-80]);
    check([speech shouldFinishAt:30 power:NAN]);
    puts("Wake policy: exact keyword, unrelated words, speech/silence and duration checks passed");return 0;
}}
