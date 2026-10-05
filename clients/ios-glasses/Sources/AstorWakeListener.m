#import "AstorWakeListener.h"
#import "AstorWakeWord.h"
#import <AVFoundation/AVFoundation.h>
#import <Speech/Speech.h>

@interface AstorWakeListener ()
@property(nonatomic,readwrite) BOOL running, starting;
@property(nonatomic,strong) AVAudioEngine *engine;
@property(nonatomic,strong) SFSpeechRecognizer *recognizer;
@property(nonatomic,strong) SFSpeechAudioBufferRecognitionRequest *request;
@property(nonatomic,strong) SFSpeechRecognitionTask *task;
@property(nonatomic,assign) NSUInteger generation;
@property(nonatomic,assign) BOOL tapped;
@property(nonatomic,copy) void (^trigger)(void);
@property(nonatomic,copy) void (^status)(NSString *);
@end
@implementation AstorWakeListener
+ (NSDictionary *)capabilities {
    SFSpeechRecognizer *r=[[SFSpeechRecognizer alloc]initWithLocale:[NSLocale localeWithLocaleIdentifier:@"ru-RU"]];
    return @{@"localRussian":@(r.supportsOnDeviceRecognition),@"available":@(r.isAvailable),@"authorization":@(SFSpeechRecognizer.authorizationStatus)};
}
+ (BOOL)containsWakeWord:(NSString *)word {
    return AstorIsWakeWord(word);
}
- (void)startWithTrigger:(void (^)(void))trigger status:(void (^)(NSString *))status {
    if(self.running || self.starting)return;
    [self stop];self.trigger=trigger;self.status=status;
    self.recognizer=[[SFSpeechRecognizer alloc]initWithLocale:[NSLocale localeWithLocaleIdentifier:@"ru-RU"]];
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
- (void)stop {
    self.generation++;self.running=NO;self.starting=NO;
    [self.engine stop];if(self.tapped){[self.engine.inputNode removeTapOnBus:0];self.tapped=NO;}
    [self.request endAudio];[self.task cancel];self.task=nil;self.request=nil;self.engine=nil;
}
- (void)dealloc { [self stop]; }
@end
