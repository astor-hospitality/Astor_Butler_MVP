#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN

@interface AstorOfflineWakeDecoder : NSObject
@property(nonatomic,readonly) BOOL timedOut;
+ (BOOL)bundled;
+ (nullable NSURL *)modelURL;
- (nullable instancetype)initWithModelURL:(NSURL *)url;
// Call only on one serial worker. Samples <= 3.256s, 16k mono float PCM.
- (BOOL)detectSamples:(NSData *)samples;
- (void)cancel;
@end
NS_ASSUME_NONNULL_END
