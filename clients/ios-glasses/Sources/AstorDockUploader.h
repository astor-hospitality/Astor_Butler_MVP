#import <Foundation/Foundation.h>
#import "AstorDockQueue.h"
NS_ASSUME_NONNULL_BEGIN
@interface AstorDockUploader : NSObject <NSURLSessionDataDelegate,NSURLSessionTaskDelegate>
@property(nonatomic,copy,nullable) void (^changed)(NSString *status);
- (instancetype)initWithQueue:(AstorDockQueue *)queue;
- (void)configureBaseURL:(nullable NSURL *)baseURL bearer:(nullable NSString *)bearer;
- (void)pump;
- (void)retry;
- (BOOL)handleBackgroundSession:(NSString *)identifier completion:(void (^)(void))completion;
@end
NS_ASSUME_NONNULL_END
