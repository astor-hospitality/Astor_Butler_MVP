#import <Foundation/Foundation.h>
#import <math.h>
// Bounded silence heuristic for wake-started questions, independent of transcript content.
@interface AstorVoiceActivity : NSObject
@property(nonatomic,assign) BOOL heardSpeech;
@property(nonatomic,assign) NSTimeInterval lastSpeech;
- (BOOL)shouldFinishAt:(NSTimeInterval)elapsed power:(float)power;
@end
