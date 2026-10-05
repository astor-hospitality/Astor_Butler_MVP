#import "AstorDockSync.h"
@interface AstorDockSync ()
@property(nonatomic) AstorDockQueue *queue;
@property(nonatomic) id<AIBudsDeviceConvertible> device;
@property(nonatomic) id<AIBudsDeviceMediaFileImportAPI> operationDevice;
@property(nonatomic) BOOL running, foreground, idle, cancelling;
@property(nonatomic) NSUInteger generation;
@property(nonatomic) NSTimer *deadline;
@property(nonatomic) NSDate *nextAttempt;
@end
@implementation AstorDockSync
- (instancetype)initWithQueue:(AstorDockQueue *)queue {if((self=[super init]))_queue=queue;return self;}
- (void)status:(NSString *)status {if(self.changed)self.changed(status);}
- (void)updateDevice:(id<AIBudsDeviceConvertible>)device foreground:(BOOL)foreground idle:(BOOL)idle {
    BOOL changed=self.device!=device;self.device=device;self.foreground=foreground;self.idle=idle;
    if(self.running && (changed || !foreground || !idle || !device.isConnectedAndReady)){if(!self.cancelling)[self cancelWithStatus:@"Архив · импорт прерван, продолжим после подключения"];return;}
    NSDictionary *session=device?[self.queue sessionForDevice:device.uuid.UUIDString]:nil;
    if(session && ![session[@"state"] isEqual:@"recording"] && !self.running && self.nextAttempt.timeIntervalSinceNow<=0 && self.wifiImportEnabled && foreground && idle && device.isConnectedAndReady)[self fetchBaseline:NO];
}
- (void)observeChargingForDevice:(id<AIBudsDeviceConvertible>)device component:(NSInteger)component state:(NSInteger)state {
    if(![self.queue observeChargingForDevice:device.uuid.UUIDString component:component state:state])return;
    [self status:@"Архив · очки заряжаются, сессия поставлена на выгрузку"];
    [self updateDevice:self.device foreground:self.foreground idle:self.idle];
}
- (BOOL)canStart {
    if(self.running)return NO;
    if(!self.wifiImportEnabled){[self status:@"Архив · для импорта нужна сборка с доступом к Wi-Fi очков"];return NO;}
    if(!self.foreground || !self.idle){[self status:@"Архив · откройте приложение после завершения записи или звонка"];return NO;}
    if(!self.device.isConnectedAndReady || ![self.device conformsToProtocol:@protocol(AIBudsDeviceMediaFileImportAPI)]){[self status:@"Архив · подключите очки"];return NO;}
    if(self.queue.storageError){[self status:@"Архив · очередь недоступна, записи остаются на очках"];return NO;}return YES;
}
- (void)prepareSession {
    if(![self canStart])return;
    if([self.queue sessionForDevice:self.device.uuid.UUIDString]){[self status:@"Архив · предыдущая сессия ещё открыта. Нажмите «Выгрузить сейчас»."];return;}
    [self fetchBaseline:YES];
}
- (void)importNow {
    if(![self canStart])return;
    if(![self.queue requestImportForDevice:self.device.uuid.UUIDString]){[self status:@"Архив · перед съёмкой подготовьте сессию"];return;}
    self.nextAttempt=nil;[self fetchBaseline:NO];
}
- (void)retry {self.nextAttempt=nil;[self updateDevice:self.device foreground:self.foreground idle:self.idle];}
- (void)cancelWithStatus:(NSString *)status {
    if(self.cancelling)return;self.cancelling=YES;
    self.generation++;[self.deadline invalidate];self.deadline=nil;self.nextAttempt=[NSDate dateWithTimeIntervalSinceNow:120];
    id<AIBudsDeviceMediaFileImportAPI> device=self.operationDevice;
    __weak typeof(self) weak=self;
    [device cancelMediaFileImportWithCompletion:^(BOOL success,NSNumber *code,NSError *error){dispatch_async(dispatch_get_main_queue(),^{weak.running=NO;weak.cancelling=NO;weak.operationDevice=nil;[weak status:status];});}];
}
- (void)armDeadline:(NSTimeInterval)duration {
    [self.deadline invalidate];NSUInteger generation=self.generation;__weak typeof(self) weak=self;
    self.deadline=[NSTimer scheduledTimerWithTimeInterval:duration repeats:NO block:^(NSTimer *timer){if(weak.running && weak.generation==generation)[weak cancelWithStatus:@"Архив · время импорта истекло, записи сохранены на очках"];}];
}
- (void)finish:(BOOL)success deviceId:(NSString *)deviceId baseline:(BOOL)baseline {
    [self.deadline invalidate];self.deadline=nil;
    if(!baseline)success=[self.queue finishImportForDevice:deviceId success:success];
    self.generation++;self.cancelling=YES;self.nextAttempt=success?nil:[NSDate dateWithTimeIntervalSinceNow:120];
    void (^done)(void)=^{
        self.running=NO;self.cancelling=NO;self.operationDevice=nil;
        [self status:success?(baseline?@"Архив · сессия готова. Новые записи выгрузим при зарядке.":@"Архив · новые записи сохранены на iPhone"): @"Архив · импорт не завершён. Повторите, когда очки доступны по Wi-Fi."];
        if(!baseline && self.filesReady)self.filesReady();
    };
    if(baseline){done();return;}
    [self.operationDevice cancelMediaFileImportWithCompletion:^(BOOL cancelled,NSNumber *code,NSError *error){dispatch_async(dispatch_get_main_queue(),done);}];
}
- (void)fetchBaseline:(BOOL)baseline {
    if(![self canStart])return;
    self.running=YES;NSUInteger generation=++self.generation;NSString *deviceId=self.device.uuid.UUIDString;
    self.operationDevice=(id<AIBudsDeviceMediaFileImportAPI>)self.device;[self armDeadline:180];
    [self status:baseline?@"Архив · запоминаем файлы до съёмки. Разрешите подключение к Wi-Fi очков.":@"Архив · ищем новые записи через Wi-Fi очков"];
    __weak typeof(self) weak=self;
    [self.operationDevice fetchMediaFilesInfoWithConfigureHotspotStartingHandler:nil hotspotConfigureCompletionHandler:nil enterFileTransferModeStartingHandler:nil enterFileTransferModeCompletedHandler:nil waitingForHotspotOpenHandler:nil connectDeviceHotspotStartingHandler:^(NSString *ssid){dispatch_async(dispatch_get_main_queue(),^{if(weak.generation==generation)[weak status:@"Архив · разрешите подключение к Wi-Fi очков"];});} deviceHotspotConnectCompletionHandler:nil completionHandler:^(BOOL success,NSArray<AIBudsMediaFileInfoModel *> *files,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        typeof(self) self=weak;if(!self || self.generation!=generation || !self.running)return;
        if(!success || ![files isKindOfClass:NSArray.class]){[self finish:NO deviceId:deviceId baseline:baseline];return;}
        NSMutableArray *names=[NSMutableArray new];for(AIBudsMediaFileInfoModel *file in files){if(!file.deviceFileName.length){[self finish:NO deviceId:deviceId baseline:baseline];return;}[names addObject:file.deviceFileName];}
        if(baseline){
            // Release the SDK transfer preparation before allowing another device operation.
            [self.operationDevice cancelMediaFileImportWithCompletion:^(BOOL cancelled,NSNumber *code,NSError *cancelError){dispatch_async(dispatch_get_main_queue(),^{
                if(self.generation!=generation || !self.running)return;
                BOOL armed=cancelled && [self.queue beginSessionForDevice:deviceId baseline:names error:nil];[self finish:armed deviceId:deviceId baseline:YES];
            });}];return;
        }
        if(![self.queue freezeInventory:names device:deviceId]){[self finish:NO deviceId:deviceId baseline:NO];return;}
        NSSet *pending=[NSSet setWithArray:[self.queue pendingDeviceNames:names device:deviceId]];
        NSMutableArray *selected=[NSMutableArray new];BOOL unsupported=NO;
        for(AIBudsMediaFileInfoModel *file in files)if([pending containsObject:file.deviceFileName]){if([self mime:file]) [selected addObject:file];else unsupported=YES;}
        if(!selected.count){[self finish:!unsupported deviceId:deviceId baseline:NO];return;}
        [self importFiles:selected deviceId:deviceId generation:generation unsupported:unsupported];
    });}];
}
- (NSString *)mime:(AIBudsMediaFileInfoModel *)file {
    switch(file.fileType){case AIBudsMediaFileTypeImage:return @"image/jpeg";case AIBudsMediaFileTypeAudio:return @"audio/mp4";case AIBudsMediaFileTypeVideo:return @"video/mp4";default:return nil;}
}
- (void)importFiles:(NSArray<AIBudsMediaFileInfoModel *> *)files deviceId:(NSString *)deviceId generation:(NSUInteger)generation unsupported:(BOOL)unsupported {
    [self armDeadline:600];[self status:@"Архив · переносим записи с очков на iPhone"];
    __block BOOL storedAll=!unsupported;__weak typeof(self) weak=self;
    [self.operationDevice importMediaFiles:files dataChunkHandler:^(NSData *chunk,NSString *taskId,NSString *url,uint64_t fileSize,uint64_t transferred,NSError *error){
        if(fileSize>64ULL*1024*1024 || transferred>64ULL*1024*1024)dispatch_async(dispatch_get_main_queue(),^{if(weak.generation==generation && weak.running)[weak cancelWithStatus:@"Архив · запись больше 64 МБ, исходник остаётся на очках"];});
    } singleTransferStartingHandler:nil singleTransferCompletionHandler:^(BOOL success,AIBudsImportedMediaFileModel *result,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        typeof(self) self=weak;if(!self || self.generation!=generation || !self.running)return;
        if(!success || !result.localFileURL || ![self.queue stageFile:result.localFileURL device:deviceId name:result.metadata.deviceFileName mime:[self mime:result.metadata] error:nil])storedAll=NO;
        if(self.filesReady)self.filesReady();
    });} transferSpeedHandler:nil transferBatchProgressHandler:nil videoStabilizationPhaseBeginHandler:nil videoStabilizationSingleFileProgressHandler:nil videoStabilizationSingleFileCompletionHandler:nil videoStabilizationBatchProgressHandler:nil videoStabilizationPhaseFinishHandler:nil completionHandler:^(BOOL success,NSArray<AIBudsImportedMediaFileModel *> *results,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        typeof(self) self=weak;if(!self || self.generation!=generation || !self.running)return;
        // Batch results also cover SDKs that omit the per-file callback. Staging is idempotent.
        for(AIBudsImportedMediaFileModel *result in results)if(!result.localFileURL || ![self.queue stageFile:result.localFileURL device:deviceId name:result.metadata.deviceFileName mime:[self mime:result.metadata] error:nil])storedAll=NO;
        NSSet *remaining=[NSSet setWithArray:[self.queue pendingDeviceNames:[files valueForKey:@"deviceFileName"] device:deviceId]];
        [self finish:success && storedAll && !remaining.count deviceId:deviceId baseline:NO];
    });}];
}
@end
