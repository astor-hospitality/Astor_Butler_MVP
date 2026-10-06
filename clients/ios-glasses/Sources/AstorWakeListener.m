#import "AstorWakeListener.h"
#import "AstorWakeWord.h"
#import "AstorWakeBuffer.h"
#import "AstorOfflineWakeDecoder.h"
#import <AVFoundation/AVFoundation.h>
#import <Speech/Speech.h>
#include <stdatomic.h>
#include <math.h>

@interface AstorWakeListener ()
@property(nonatomic,readwrite) BOOL running, starting;
@property(nonatomic,strong) AVAudioEngine *engine;
@property(nonatomic,strong) SFSpeechRecognizer *recognizer;
@property(nonatomic,strong) SFSpeechAudioBufferRecognitionRequest *request;
@property(nonatomic,strong) SFSpeechRecognitionTask *task;
@property(nonatomic,strong) AstorOfflineWakeDecoder *offlineDecoder;
@property(nonatomic,strong) dispatch_queue_t decodeQueue;
@property(nonatomic,assign) BOOL usingOffline;
@property(nonatomic,assign) NSUInteger generation;
@property(nonatomic,assign) BOOL tapped;
@property(nonatomic,copy) void (^trigger)(void);
@property(nonatomic,copy) void (^status)(NSString *);
@end
@implementation AstorWakeListener {
    atomic_bool _decoding;
}
- (instancetype)init {
    if((self=[super init])){self.decodeQueue=dispatch_queue_create("astor.local-wake",DISPATCH_QUEUE_SERIAL);atomic_init(&_decoding,false);}
    return self;
}
+ (NSDictionary *)capabilities {
    SFSpeechRecognizer *r=[[SFSpeechRecognizer alloc]initWithLocale:[NSLocale localeWithLocaleIdentifier:@"ru-RU"]];
    BOOL offline=[AstorOfflineWakeDecoder bundled];
    return @{@"localRussian":@(r.supportsOnDeviceRecognition || offline),@"appleLocalRussian":@(r.supportsOnDeviceRecognition),
             @"offlineModel":@(offline),@"available":@(r.isAvailable || offline),@"authorization":@(SFSpeechRecognizer.authorizationStatus)};
}
+ (BOOL)containsWakeWord:(NSString *)word {
    return AstorIsWakeWord(word);
}
- (void)startWithTrigger:(void (^)(void))trigger status:(void (^)(NSString *))status {
    if(self.running || self.starting)return;
    [self stop];self.trigger=trigger;self.status=status;
    self.recognizer=[[SFSpeechRecognizer alloc]initWithLocale:[NSLocale localeWithLocaleIdentifier:@"ru-RU"]];
    self.usingOffline=(!self.recognizer.supportsOnDeviceRecognition || !self.recognizer.isAvailable) && [AstorOfflineWakeDecoder bundled];
    if(self.usingOffline){
        self.starting=YES;NSUInteger generation=self.generation;__weak typeof(self) weak=self;
        status(@"Загружаю локальную модель команды «Астор».");
        dispatch_async(self.decodeQueue,^{
            if(generation!=weak.generation)return;
            AstorOfflineWakeDecoder *decoder=[[AstorOfflineWakeDecoder alloc]initWithModelURL:AstorOfflineWakeDecoder.modelURL];
            dispatch_async(dispatch_get_main_queue(),^{
                if(generation!=weak.generation){[decoder cancel];return;}
                if(!decoder){weak.starting=NO;status(@"Локальная модель не загрузилась. Микрофон не включён.");return;}
                weak.offlineDecoder=decoder;
                [AVAudioSession.sharedInstance requestRecordPermission:^(BOOL allowed){
                    dispatch_async(dispatch_get_main_queue(),^{
                        if(generation!=weak.generation)return;
                        if(!allowed){[weak stop];status(@"Разрешите микрофон для команды «Астор».");return;}
                        [weak prepareInput:generation];
                    });
                }];
            });
        });return;
    }
    if(!self.recognizer.supportsOnDeviceRecognition){status(@"Русское локальное распознавание недоступно на этом iPhone. Микрофон не включён.");return;}
    if(!self.recognizer.isAvailable){status(@"Локальное распознавание сейчас недоступно. Включите режим снова позже.");return;}
    self.starting=YES;NSUInteger generation=self.generation;
    __weak typeof(self) weak=self;
    [SFSpeechRecognizer requestAuthorization:^(SFSpeechRecognizerAuthorizationStatus auth){
        dispatch_async(dispatch_get_main_queue(),^{
            if(generation!=weak.generation)return;
            if(auth!=SFSpeechRecognizerAuthorizationStatusAuthorized){weak.starting=NO;status(@"Разрешите распознавание речи в настройках iPhone.");return;}
            [AVAudioSession.sharedInstance requestRecordPermission:^(BOOL allowed){
                dispatch_async(dispatch_get_main_queue(),^{
                    if(generation!=weak.generation)return;
                    if(!allowed){weak.starting=NO;status(@"Разрешите микрофон для команды «Астор».");return;}
                    [weak prepareInput:generation];
                });
            }];
        });
    }];
}
- (void)prepareInput:(NSUInteger)generation {
    AVAudioSession *s=AVAudioSession.sharedInstance;NSError *error=nil;
    AVAudioSessionPortDescription *input=nil;
    for(AVAudioSessionPortDescription *p in s.availableInputs)
        if([p.portType isEqual:AVAudioSessionPortBluetoothHFP] && ([p.portName.lowercaseString containsString:@"ai glasses"] || [p.portName.lowercaseString containsString:@"563b"]))input=p;
    if(!input || ![s setCategory:AVAudioSessionCategoryPlayAndRecord mode:AVAudioSessionModeDefault options:AVAudioSessionCategoryOptionAllowBluetooth error:&error] || ![s setActive:YES error:&error] || ![s setPreferredInput:input error:&error]){
        self.starting=NO;self.status(@"Для команды «Астор» нужен Bluetooth-микрофон очков.");return;
    }
    __weak typeof(self) weak=self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,400*NSEC_PER_MSEC),dispatch_get_main_queue(),^{if(generation==weak.generation)[weak startEngine:generation];});
}
- (void)startEngine:(NSUInteger)generation {
    BOOL glassesInput=NO;
    for(AVAudioSessionPortDescription *p in AVAudioSession.sharedInstance.currentRoute.inputs)
        if([p.portType isEqual:AVAudioSessionPortBluetoothHFP] && ([p.portName.lowercaseString containsString:@"ai glasses"] || [p.portName.lowercaseString containsString:@"563b"]))glassesInput=YES;
    if(!glassesInput){self.starting=NO;self.status(@"Микрофон очков не стал активным. Телефон вместо него не используется.");return;}
    self.engine=[AVAudioEngine new];AVAudioInputNode *input=self.engine.inputNode;
    AVAudioFormat *format=[input outputFormatForBus:0];
    if(format.sampleRate<=0 || format.channelCount<1){[self stop];self.status(@"Не удалось открыть формат микрофона очков.");return;}
    if(self.usingOffline){[self startOfflineEngine:input format:format generation:generation];return;}
    self.request=[SFSpeechAudioBufferRecognitionRequest new];self.request.requiresOnDeviceRecognition=YES;
    self.request.shouldReportPartialResults=YES;self.request.contextualStrings=@[@"Астор"];
    SFSpeechAudioBufferRecognitionRequest *request=self.request;
    [input installTapOnBus:0 bufferSize:1024 format:format block:^(AVAudioPCMBuffer *buffer,AVAudioTime *when){[request appendAudioPCMBuffer:buffer];}];self.tapped=YES;
    __weak typeof(self) weak=self;
    self.task=[self.recognizer recognitionTaskWithRequest:request resultHandler:^(SFSpeechRecognitionResult *result,NSError *error){
        dispatch_async(dispatch_get_main_queue(),^{
            if(generation!=weak.generation)return;
            for(SFTranscriptionSegment *segment in result.bestTranscription.segments)if([AstorWakeListener containsWakeWord:segment.substring]){
                void (^trigger)(void)=weak.trigger;[weak stop];if(trigger)trigger();return;
            }
            if(error){void (^status)(NSString *)=weak.status;[weak stop];if(status)status(@"Локальное распознавание остановлено. Включите режим снова.");}
            else if(result.isFinal){void (^trigger)(void)=weak.trigger;void (^status)(NSString *)=weak.status;[weak stop];if(status)status(@"Обновляю локальное прослушивание.");dispatch_after(dispatch_time(DISPATCH_TIME_NOW,500*NSEC_PER_MSEC),dispatch_get_main_queue(),^{if(generation+1==weak.generation)[weak startWithTrigger:trigger status:status];});}
        });
    }];
    NSError *error=nil;[self.engine prepare];
    if(![self.engine startAndReturnError:&error]){[self stop];self.status(@"Микрофон не запустился. Команда «Астор» выключена.");return;}
    self.starting=NO;self.running=YES;self.status(@"● Слушаю «Астор» локально через микрофон очков");
    // Rotate bounded recognizer sessions; old callbacks are rejected by generation.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,50*NSEC_PER_SEC),dispatch_get_main_queue(),^{
        if(generation!=weak.generation || !weak.running)return;
        void (^trigger)(void)=weak.trigger;void (^status)(NSString *)=weak.status;[weak stop];
        if(status)status(@"Обновляю локальное прослушивание.");
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW,500*NSEC_PER_MSEC),dispatch_get_main_queue(),^{if(generation+1==weak.generation)[weak startWithTrigger:trigger status:status];});
    });
}
- (void)startOfflineEngine:(AVAudioInputNode *)input format:(AVAudioFormat *)format generation:(NSUInteger)generation {
    AVAudioFormat *target=[[AVAudioFormat alloc]initWithCommonFormat:AVAudioPCMFormatFloat32 sampleRate:16000 channels:1 interleaved:NO];
    AVAudioConverter *converter=[[AVAudioConverter alloc]initFromFormat:format toFormat:target];
    if(!converter){[self stop];self.status(@"Не удалось подготовить локальный микрофон.");return;}
    AstorWakeBuffer *window=[AstorWakeBuffer new];AstorOfflineWakeDecoder *decoder=self.offlineDecoder;
    __weak typeof(self) weak=self;dispatch_queue_t queue=self.decodeQueue;
    [input installTapOnBus:0 bufferSize:1024 format:format block:^(AVAudioPCMBuffer *buffer,AVAudioTime *when){
        AstorWakeListener *current=weak;
        if(!current || generation!=current.generation || !current.running)return;
        if(atomic_load(&current->_decoding)){[window reset];return;}
        AVAudioFrameCount capacity=(AVAudioFrameCount)ceil(buffer.frameLength*16000.0/format.sampleRate)+16;
        if(capacity>4096){[window reset];return;}
        AVAudioPCMBuffer *out=[[AVAudioPCMBuffer alloc]initWithPCMFormat:target frameCapacity:capacity];
        __block BOOL consumed=NO;NSError *error=nil;
        [converter convertToBuffer:out error:&error withInputFromBlock:^AVAudioBuffer *(AVAudioPacketCount count,AVAudioConverterInputStatus *state){
            if(consumed){*state=AVAudioConverterInputStatus_NoDataNow;return nil;}
            consumed=YES;*state=AVAudioConverterInputStatus_HaveData;return buffer;
        }];
        if(error || !out.frameLength || !out.floatChannelData)return;
        NSData *utterance=[window appendSamples:[NSData dataWithBytes:out.floatChannelData[0] length:out.frameLength*sizeof(float)]];
        if(!utterance || atomic_exchange(&current->_decoding,true))return;
        dispatch_async(queue,^{
            BOOL matched=[decoder detectSamples:utterance];BOOL slow=decoder.timedOut;
            AstorWakeListener *alive=weak;if(alive)atomic_store(&alive->_decoding,false);
            dispatch_async(dispatch_get_main_queue(),^{
                if(generation!=weak.generation || !weak.running)return;
                if(slow){void (^status)(NSString *)=weak.status;[weak stop];if(status)status(@"Локальная модель слишком медленная на этом iPhone. Режим выключен.");return;}
                if(matched){void (^trigger)(void)=weak.trigger;[weak stop];if(trigger)trigger();}
            });
        });
    }];self.tapped=YES;
    NSError *error=nil;[self.engine prepare];
    if(![self.engine startAndReturnError:&error]){[self stop];self.status(@"Локальный микрофон не запустился.");return;}
    self.starting=NO;self.running=YES;self.status(@"● Слушаю «Астор» локально · Whisper CPU");
}
- (void)stop {
    self.generation++;self.running=NO;self.starting=NO;
    [self.engine stop];if(self.tapped){[self.engine.inputNode removeTapOnBus:0];self.tapped=NO;}
    [self.request endAudio];[self.task cancel];self.task=nil;self.request=nil;self.engine=nil;
    [self.offlineDecoder cancel];self.offlineDecoder=nil;
}
- (void)dealloc { [self stop]; }
@end
