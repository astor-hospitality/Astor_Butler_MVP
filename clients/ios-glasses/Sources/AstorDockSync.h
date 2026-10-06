#import <Foundation/Foundation.h>
#import <AIBuds/AIBuds.h>
#import "AstorDockQueue.h"
NS_ASSUME_NONNULL_BEGIN
@interface AstorDockSync : NSObject
@property(nonatomic,readonly) BOOL running;
@property(nonatomic) BOOL wifiImportEnabled;
@property(nonatomic,copy,nullable) void (^changed)(NSString *status);
@property(nonatomic,copy,nullable) void (^filesReady)(void);
- (instancetype)initWithQueue:(AstorDockQueue *)queue;
- (void)updateDevice:(nullable id<AIBudsDeviceConvertible>)device foreground:(BOOL)foreground idle:(BOOL)idle;
- (void)observeChargingForDevice:(id<AIBudsDeviceConvertible>)device component:(NSInteger)component state:(NSInteger)state;
- (void)prepareSession;
- (void)importNow;
- (void)retry;
@end
NS_ASSUME_NONNULL_END
