#import <Foundation/Foundation.h>

@interface AstorWakeListener : NSObject
@property(nonatomic,readonly) BOOL running, starting;
+ (NSDictionary *)capabilities;
+ (BOOL)containsWakeWord:(NSString *)word;
- (void)startWithTrigger:(void (^)(void))trigger status:(void (^)(NSString *))status;
- (void)stop;
@end
