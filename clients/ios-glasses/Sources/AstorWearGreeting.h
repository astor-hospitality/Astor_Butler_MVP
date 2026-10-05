#import <Foundation/Foundation.h>

// Values come from SDK WearStatus: unknown=-1, off=0, worn=1..3.
@interface AstorWearGreeting : NSObject
@property(nonatomic,strong) NSDate *lastGreeting;
- (void)observeStatus:(NSInteger)status at:(NSDate *)now;
- (BOOL)consumeAt:(NSDate *)now allowed:(BOOL)allowed;
@end
