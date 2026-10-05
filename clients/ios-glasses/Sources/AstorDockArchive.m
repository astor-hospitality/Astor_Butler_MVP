#import "AstorDockArchive.h"
#import "AstorDockQueue.h"
#import "AstorDockSync.h"
#import "AstorDockUploader.h"
@interface AstorDockArchive ()
@property(nonatomic) AstorDockQueue *queue;
@property(nonatomic) AstorDockSync *sync;
@property(nonatomic) AstorDockUploader *uploader;
@property(nonatomic) UILabel *label, *progress;
@property(nonatomic) id<AIBudsDeviceConvertible> device;
@property(nonatomic) BOOL foreground, idle;
@property(nonatomic) NSString *pendingChargingDevice;
@property(nonatomic) NSDate *pendingChargingAt;
@end
@implementation AstorDockArchive
+ (instancetype)shared {static AstorDockArchive *archive;static dispatch_once_t once;dispatch_once(&once,^{archive=[AstorDockArchive new];});return archive;}
- (instancetype)init {
    if(!(self=[super init]))return nil;
    NSURL *directory=[[NSFileManager.defaultManager URLsForDirectory:NSApplicationSupportDirectory inDomains:NSUserDomainMask].firstObject URLByAppendingPathComponent:@"AstorDockArchive" isDirectory:YES];
    _queue=[[AstorDockQueue alloc]initWithDirectory:directory];_sync=[[AstorDockSync alloc]initWithQueue:_queue];_uploader=[[AstorDockUploader alloc]initWithQueue:_queue];
    // Enabled only in a build whose signed HotspotConfiguration entitlement was verified.
    _sync.wifiImportEnabled=[NSBundle.mainBundle.infoDictionary[@"AstorWiFiImportEnabled"] boolValue];
    __weak typeof(self) weak=self;
    _sync.changed=^(NSString *status){[weak status:status];};
    _uploader.changed=^(NSString *status){[weak status:status];};
    _sync.filesReady=^{[weak.uploader pump];[weak refresh];};return self;
}
- (BOOL)busy {return self.sync.running;}
- (void)status:(NSString *)status {self.progress.text=status;[self refresh];if(self.changed)self.changed(status);}
- (void)refresh {self.label.text=[self.queue summaryForDevice:self.device.uuid.UUIDString];}
- (UIButton *)button:(NSString *)title action:(SEL)action {
    UIButton *button=[UIButton buttonWithType:UIButtonTypeSystem];[button setTitle:title forState:UIControlStateNormal];
    button.titleLabel.font=[UIFont preferredFontForTextStyle:UIFontTextStyleHeadline];button.titleLabel.adjustsFontForContentSizeCategory=YES;button.titleLabel.numberOfLines=0;
    [button setTitleColor:[UIColor colorWithWhite:.9 alpha:1] forState:UIControlStateNormal];[button.heightAnchor constraintGreaterThanOrEqualToConstant:48].active=YES;
    [button addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];return button;
}
- (UIStackView *)makePanel {
    self.label=[UILabel new];self.label.numberOfLines=0;self.label.textColor=[UIColor colorWithWhite:.9 alpha:1];self.label.font=[UIFont preferredFontForTextStyle:UIFontTextStyleBody];self.label.adjustsFontForContentSizeCategory=YES;
    self.progress=[UILabel new];self.progress.numberOfLines=0;self.progress.textColor=[UIColor colorWithWhite:.7 alpha:1];self.progress.font=[UIFont preferredFontForTextStyle:UIFontTextStyleFootnote];self.progress.adjustsFontForContentSizeCategory=YES;
    self.progress.text=self.sync.wifiImportEnabled?@"Перед съёмкой подготовьте сессию. Зарядка очков поставит новые записи на выгрузку. Исходники остаются на очках.":@"В этой сборке архивируем фото и голос, полученные приложением. Сначала подготовьте сессию. Записи в памяти очков требуют доступа к их Wi-Fi.";
    UIStackView *panel=[[UIStackView alloc]initWithArrangedSubviews:@[self.label,self.progress,[self button:@"Подготовить архив сессии" action:@selector(prepare)],[self button:@"Выгрузить сейчас" action:@selector(importNow)],[self button:@"Повторить отправку" action:@selector(retry)]]];
    panel.axis=UILayoutConstraintAxisVertical;panel.spacing=12;[self refresh];return panel;
}
- (BOOL)canCaptureStart {return self.foreground && self.device.isConnectedAndReady && !self.busy;}
- (void)prepare {
    if(self.sync.wifiImportEnabled){if(self.prepareForImport)self.prepareForImport();[self.sync prepareSession];return;}
    if(![self canCaptureStart]){[self status:@"Архив · подключите очки и завершите запись или звонок"];return;}
    if([self.queue beginCapturedSessionForDevice:self.device.uuid.UUIDString])[self status:@"Архив готов · фото и голос сохраняются до зарядки очков"];
    else [self status:@"Архив · предыдущая сессия открыта. Нажмите «Выгрузить сейчас»."];
}
- (void)importNow {
    if(self.prepareForImport)self.prepareForImport();
    if(self.sync.wifiImportEnabled){[self.sync importNow];return;}
    if(!self.idle || !self.foreground){[self status:@"Архив · сначала завершите запись или звонок"];return;}
    if([self.queue requestImportForDevice:self.device.uuid.UUIDString]){[self.uploader pump];[self status:@"Архив · сессия поставлена на отправку"];}else [self status:@"Архив · сначала подготовьте сессию"];
}
- (void)retry {[self.sync retry];[self.uploader retry];[self refresh];}
- (void)configureBaseURL:(NSURL *)baseURL bearer:(NSString *)bearer {[self.uploader configureBaseURL:baseURL bearer:bearer];}
- (void)updateDevice:(id<AIBudsDeviceConvertible>)device foreground:(BOOL)foreground idle:(BOOL)idle {
    self.device=device;self.foreground=foreground;self.idle=idle;
    if(self.pendingChargingAt && -self.pendingChargingAt.timeIntervalSinceNow<120 && idle && device.isConnectedAndReady && [self.pendingChargingDevice isEqual:device.uuid.UUIDString]) {
        self.pendingChargingAt=nil;[self.sync observeChargingForDevice:device component:0 state:1];
    }
    [self.sync updateDevice:device foreground:foreground idle:idle];[self.uploader pump];[self refresh];
}
- (void)observeChargingForDevice:(id<AIBudsDeviceConvertible>)device component:(NSInteger)component state:(NSInteger)state {
    if(component==0 && state==0){self.pendingChargingAt=nil;self.pendingChargingDevice=nil;}
    if(component==0 && state==1 && !self.idle){self.pendingChargingDevice=device.uuid.UUIDString;self.pendingChargingAt=NSDate.date;return;}
    [self.sync observeChargingForDevice:device component:component state:state];[self.uploader pump];[self refresh];
}
- (BOOL)capturePhotoData:(NSData *)jpeg {
    if(!jpeg.length || jpeg.length>2*1024*1024 || ![self.queue sessionForDevice:self.device.uuid.UUIDString])return NO;
    NSURL *temp=[NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingString:@".capture"]]];
    BOOL written=[jpeg writeToURL:temp options:NSDataWritingAtomic error:nil];
    BOOL saved=written && [self.queue stageCapturedFile:temp device:self.device.uuid.UUIDString mime:@"image/jpeg" error:nil];
    [NSFileManager.defaultManager removeItemAtURL:temp error:nil];
    if(saved)[self status:@"Архив · фото сохранено на iPhone до зарядки"];return saved;
}
- (BOOL)captureAudioFile:(NSURL *)file {
    if(!file || !self.device)return NO;
    BOOL saved=[self.queue stageCapturedFile:file device:self.device.uuid.UUIDString mime:@"audio/mp4" error:nil];
    if(saved)[self status:@"Архив · голос сохранён на iPhone до зарядки"];return saved;
}
- (BOOL)handleBackgroundSession:(NSString *)identifier completion:(void (^)(void))completion {return [self.uploader handleBackgroundSession:identifier completion:completion];}
@end
