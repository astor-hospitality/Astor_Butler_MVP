#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN

// Mono float PCM, 16 kHz. No disk/network access. One short utterance at a time.
@interface AstorWakeBuffer : NSObject
@property(nonatomic,readonly) NSUInteger retainedBytes;
- (nullable NSData *)appendSamples:(NSData *)samples;
- (void)reset;
@end
NS_ASSUME_NONNULL_END
