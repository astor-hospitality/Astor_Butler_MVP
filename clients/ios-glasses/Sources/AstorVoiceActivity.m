#import "AstorVoiceActivity.h"
@implementation AstorVoiceActivity
- (BOOL)shouldFinishAt:(NSTimeInterval)elapsed power:(float)power {
    if(isfinite(power) && power > -35){self.heardSpeech=YES;self.lastSpeech=elapsed;}
    return elapsed>=30 || (self.heardSpeech && elapsed>=2 && elapsed-self.lastSpeech>=1.8)
        || (!self.heardSpeech && elapsed>=8);
}
@end
