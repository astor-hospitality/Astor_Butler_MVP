#import "AstorWearGreeting.h"
@interface AstorWearGreeting ()
@property(nonatomic,assign) BOOL worn;
@property(nonatomic,strong) NSDate *pendingUntil;
@end
@implementation AstorWearGreeting
- (void)observeStatus:(NSInteger)status at:(NSDate *)now {
    if(status<0 || status>3){self.pendingUntil=nil;self.worn=NO;return;}
    BOOL worn=status>0;
    if(worn && !self.worn && (!self.lastGreeting || [now timeIntervalSinceDate:self.lastGreeting]>=30))
        self.pendingUntil=[now dateByAddingTimeInterval:10];
    if(!worn)self.pendingUntil=nil;
    self.worn=worn;
}
- (BOOL)consumeAt:(NSDate *)now allowed:(BOOL)allowed {
    if(!self.pendingUntil)return NO;
    if([now compare:self.pendingUntil]!=NSOrderedAscending){self.pendingUntil=nil;return NO;}
    if(!allowed || !self.worn)return NO;
    self.pendingUntil=nil;self.lastGreeting=now;return YES;
}
@end
