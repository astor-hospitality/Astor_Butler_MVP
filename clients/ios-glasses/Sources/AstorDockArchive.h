#import <UIKit/UIKit.h>
#import <AIBuds/AIBuds.h>
NS_ASSUME_NONNULL_BEGIN
// A single process-lifetime owner also receives relaunched background URLSession events.
@interface AstorDockArchive : NSObject
@property(nonatomic,readonly) BOOL busy;
@property(nonatomic,copy,nullable) void (^changed)(NSString *status);
@property(nonatomic,copy,nullable) void (^prepareForImport)(void);
+ (instancetype)shared;
- (UIStackView *)makePanel;
- (void)configureBaseURL:(nullable NSURL *)baseURL bearer:(nullable NSString *)bearer;
- (void)updateDevice:(nullable id<AIBudsDeviceConvertible>)device foreground:(BOOL)foreground idle:(BOOL)idle;
- (void)observeChargingForDevice:(id<AIBudsDeviceConvertible>)device component:(NSInteger)component state:(NSInteger)state;
- (BOOL)capturePhotoData:(NSData *)jpeg;
- (BOOL)captureAudioFile:(NSURL *)file;
- (BOOL)handleBackgroundSession:(NSString *)identifier completion:(void (^)(void))completion;
@end
NS_ASSUME_NONNULL_END
