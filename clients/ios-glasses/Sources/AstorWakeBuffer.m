#import "AstorWakeBuffer.h"
#include <math.h>

@implementation AstorWakeBuffer {
    NSMutableData *_window;
    BOOL _speech;
    NSUInteger _quiet, _voiced;
}
- (instancetype)init { if((self=[super init]))_window=[NSMutableData data];return self; }
- (NSUInteger)retainedBytes { return _window.length; }
- (void)reset { [_window setLength:0];_speech=NO;_quiet=0;_voiced=0; }
- (NSData *)appendSamples:(NSData *)samples {
    if(!samples.length || samples.length%sizeof(float) || samples.length>4096*sizeof(float)){[self reset];return nil;}
    const float *pcm=samples.bytes;NSUInteger count=samples.length/sizeof(float);double energy=0;
    for(NSUInteger i=0;i<count;i++){if(!isfinite(pcm[i]) || fabsf(pcm[i])>1.01){[self reset];return nil;}energy+=pcm[i]*pcm[i];}
    BOOL voice=sqrt(energy/count)>=0.008;
    if(!_speech && voice)_speech=YES;
    [_window appendData:samples];
    if(!_speech){
        NSUInteger keep=3200*sizeof(float);if(_window.length>keep)[_window replaceBytesInRange:NSMakeRange(0,_window.length-keep) withBytes:NULL length:0];
        return nil;
    }
    if(voice){_voiced+=count;_quiet=0;}else _quiet+=count;
    if(_quiet>=6400 || _window.length>=48000*sizeof(float)){
        NSData *result=_voiced>=3200?[_window copy]:nil;[self reset];return result;
    }
    return nil;
}
@end
