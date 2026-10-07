#import <Foundation/Foundation.h>

// Local step progress only; this object never represents a portal task or ACK.
@interface AstorLunchGuide : NSObject
@property(nonatomic,readonly) BOOL active, finished;
@property(nonatomic,readonly) NSUInteger stepIndex, revision;
@property(nonatomic,readonly) NSDictionary<NSString *,NSString *> *step;
@property(nonatomic,readonly) NSString *brief;
@property(nonatomic,readonly) NSString *compactBrief;
@property(nonatomic,readonly) NSString *sessionId, *photoStatus;
@property(nonatomic,readonly) BOOL photoRequired, photoReceived, canAdvance;
@property(nonatomic,readonly) NSUInteger photoCount;
+ (NSArray<NSDictionary<NSString *,NSString *> *> *)steps;
- (void)start;
- (BOOL)advance;
- (void)stop;
- (NSDictionary *)photoContext;
- (BOOL)acceptsPhotoContext:(NSDictionary *)context;
- (BOOL)acceptPhotoReceipt:(NSDictionary *)receipt context:(NSDictionary *)context requestId:(NSString *)requestId;
@end
