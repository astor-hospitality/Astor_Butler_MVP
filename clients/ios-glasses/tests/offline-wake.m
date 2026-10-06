#import <Foundation/Foundation.h>
#import "../Sources/AstorWakeBuffer.h"
#import "../Sources/AstorOfflineWakeDecoder.h"
#include <math.h>

static NSData *pcm(NSUInteger count,float value) {
    NSMutableData *d=[NSMutableData dataWithLength:count*sizeof(float)];float *p=d.mutableBytes;
    for(NSUInteger i=0;i<count;i++)p[i]=value;return d;
}
int main(int argc,const char **argv) { @autoreleasepool {
    AstorWakeBuffer *b=[AstorWakeBuffer new];
    for(int i=0;i<1000;i++)NSCAssert(![b appendSamples:pcm(1024,0)],@"silence emitted");
    NSCAssert(b.retainedBytes<=3200*sizeof(float),@"silence unbounded");
    for(int i=0;i<4;i++)[b appendSamples:pcm(1024,0.04)];
    NSData *utterance=nil;for(int i=0;i<7;i++)utterance=[b appendSamples:pcm(1024,0)]?:utterance;
    NSCAssert(utterance.length && utterance.length<=52096*sizeof(float),@"speech window missing/unbounded");
    NSCAssert(b.retainedBytes<=3200*sizeof(float),@"completed speech retained");
    NSCAssert(![b appendSamples:pcm(1024,NAN)] && b.retainedBytes==0,@"invalid floats");
    NSCAssert(![b appendSamples:pcm(4097,0)] && b.retainedBytes==0,@"oversized tap");
    for(int i=0;i<49;i++)utterance=[b appendSamples:pcm(1024,0.02)]?:utterance;
    NSCAssert(utterance.length<=52096*sizeof(float) && b.retainedBytes<=48000*sizeof(float),@"continuous speech unbounded");
    puts("bounded silence/speech/invalid/cap/reset PASS");
    if(argc==1){
        NSCAssert(![AstorOfflineWakeDecoder bundled],@"prototype must remain disabled");
        NSCAssert(![[AstorOfflineWakeDecoder alloc]initWithModelURL:[NSURL fileURLWithPath:@"/unavailable-model"]],@"disabled decoder must not load a model");
        puts("frozen experimental decoder OFF PASS");
    }
    if(argc==4){
        AstorOfflineWakeDecoder *d=[[AstorOfflineWakeDecoder alloc]initWithModelURL:[NSURL fileURLWithPath:@(argv[1])]];
        NSCAssert(d,@"decoder init");
        NSData *positive=[NSData dataWithContentsOfFile:@(argv[2])];NSData *negative=[NSData dataWithContentsOfFile:@(argv[3])];
        BOOL matched=[d detectSamples:positive];BOOL rejected=![d detectSamples:negative];
        printf("synthetic wake=%s negative=%s\n",matched?"PASS":"FAIL",rejected?"PASS":"FAIL");
        NSCAssert(matched && rejected,@"synthetic classifier");
        [d cancel];NSCAssert(![d detectSamples:positive],@"cancel ignored");puts("decoder cancel PASS");
    }
    return 0;
}}
