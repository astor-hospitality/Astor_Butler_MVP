#import "AstorOfflineWakeDecoder.h"
#import "AstorWakeWord.h"
#include <stdatomic.h>
#include <time.h>
#ifndef ASTOR_ENABLE_EXPERIMENTAL_WAKE
#define ASTOR_ENABLE_EXPERIMENTAL_WAKE 0
#endif
// Frozen R&D prototype. The positive synthetic wake did not pass acceptance.
#if ASTOR_ENABLE_EXPERIMENTAL_WAKE && __has_include(<whisper/whisper.h>)
#import <whisper/whisper.h>
#define ASTOR_HAS_WHISPER 1
typedef struct { atomic_bool cancelled; atomic_uint_fast64_t deadline; } AstorDecodeGuard;
static uint64_t AstorMonotonicMS(void) { struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000+t.tv_nsec/1000000; }
static bool AstorDecodeAbort(void *opaque) { AstorDecodeGuard *g=opaque;return atomic_load(&g->cancelled) || AstorMonotonicMS()>=atomic_load(&g->deadline); }
static void AstorWhisperQuiet(enum ggml_log_level level,const char *text,void *user) { }
#endif

@implementation AstorOfflineWakeDecoder {
    BOOL _timedOut;
#ifdef ASTOR_HAS_WHISPER
    struct whisper_context *_context;
    AstorDecodeGuard _guard;
#endif
}
+ (NSURL *)modelURL { return [NSBundle.mainBundle URLForResource:@"ggml-tiny-q5_1" withExtension:@"bin"]; }
+ (BOOL)bundled {
#ifdef ASTOR_HAS_WHISPER
    NSURL *url=self.modelURL;NSNumber *size=nil;[url getResourceValue:&size forKey:NSURLFileSizeKey error:nil];return size.unsignedLongLongValue==32152673;
#else
    return NO;
#endif
}
- (instancetype)initWithModelURL:(NSURL *)url {
    if(!(self=[super init]))return nil;
#ifdef ASTOR_HAS_WHISPER
    atomic_init(&_guard.cancelled,false);atomic_init(&_guard.deadline,UINT64_MAX);
    whisper_log_set(AstorWhisperQuiet,NULL);
    struct whisper_context_params p=whisper_context_default_params();p.use_gpu=false;
    _context=whisper_init_from_file_with_params(url.path.UTF8String,p);
    if(!_context)return nil;
    return self;
#else
    return nil;
#endif
}
- (BOOL)detectSamples:(NSData *)samples {
    _timedOut=NO;
#ifdef ASTOR_HAS_WHISPER
    if(!_context || atomic_load(&_guard.cancelled) || samples.length<3200*sizeof(float)
            || samples.length>52096*sizeof(float) || samples.length%sizeof(float))return NO;
    atomic_store(&_guard.deadline,AstorMonotonicMS()+4000);
    struct whisper_full_params p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language="ru";p.n_threads=2;p.no_context=true;p.no_timestamps=true;p.single_segment=true;
    p.suppress_blank=true;p.suppress_nst=true;p.max_tokens=12;p.temperature=0;p.temperature_inc=0;
    p.print_realtime=false;p.print_progress=false;p.print_timestamps=false;p.print_special=false;
    p.abort_callback=AstorDecodeAbort;p.abort_callback_user_data=&_guard;
    int result=whisper_full(_context,p,samples.bytes,(int)(samples.length/sizeof(float)));
    _timedOut=!atomic_load(&_guard.cancelled) && AstorMonotonicMS()>=atomic_load(&_guard.deadline);
    if(result!=0 || AstorDecodeAbort(&_guard) || whisper_full_n_segments(_context)!=1)return NO;
    if(whisper_full_get_segment_no_speech_prob(_context,0)>=0.5)return NO;
    const char *text=whisper_full_get_segment_text(_context,0);
    NSString *word=text?[NSString stringWithUTF8String:text]:nil;
    if(!AstorIsWakeWord(word))return NO;
    int count=0;double confidence=0;
    for(int i=0;i<whisper_full_n_tokens(_context,0);i++){
        whisper_token_data data=whisper_full_get_token_data(_context,0,i);
        if(data.id<whisper_token_eot(_context)){confidence+=data.p;count++;}
    }
    return count>0 && confidence/count>=0.5;
#else
    return NO;
#endif
}
- (BOOL)timedOut { return _timedOut; }
- (void)cancel {
#ifdef ASTOR_HAS_WHISPER
    atomic_store(&_guard.cancelled,true);
#endif
}
- (void)dealloc {
#ifdef ASTOR_HAS_WHISPER
    if(_context)whisper_free(_context);
#endif
}
@end
