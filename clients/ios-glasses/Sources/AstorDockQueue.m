#import "AstorDockQueue.h"
#import <CommonCrypto/CommonDigest.h>
#import <TargetConditionals.h>
#import <math.h>

static const unsigned long long AstorDockFileLimit=64ULL*1024*1024;
static const unsigned long long AstorDockQueueLimit=512ULL*1024*1024;
static BOOL DockNames(id names) {
    if(![names isKindOfClass:NSArray.class] || [names count]>10000)return NO;
    for(id name in names)if(![name isKindOfClass:NSString.class] || ![name length] || [name length]>1024)return NO;return YES;
}
static BOOL DockUUID(id value) {return [value isKindOfClass:NSString.class] && [[NSUUID alloc]initWithUUIDString:value]!=nil;}
static BOOL DockManifest(NSDictionary *saved) {
    for(id s in saved[@"sessions"]){
        if(![s isKindOfClass:NSDictionary.class] || !DockUUID(s[@"sessionId"]) || ![s[@"deviceId"] isKindOfClass:NSString.class] || !DockNames(s[@"baseline"]) || (s[@"endFiles"] && !DockNames(s[@"endFiles"])) || ![@[@"recording",@"waitingDevice",@"imported"] containsObject:s[@"state"]])return NO;
    }
    for(id f in saved[@"files"]){
        if(![f isKindOfClass:NSDictionary.class] || !DockUUID(f[@"fileId"]) || !DockUUID(f[@"sessionId"]) || !DockNames(@[f[@"deviceFileName"]?:NSNull.null]) || ![f[@"size"] isKindOfClass:NSNumber.class] || ![f[@"size"] unsignedLongLongValue] || [f[@"size"] unsignedLongLongValue]>AstorDockFileLimit || ![f[@"attempts"] isKindOfClass:NSNumber.class] || ![f[@"nextAttempt"] isKindOfClass:NSNumber.class] || ![@[@"held",@"pending",@"uploading",@"paused",@"confirmed"] containsObject:f[@"state"]] || ![@[@"image/jpeg",@"audio/mp4",@"video/mp4"] containsObject:f[@"mimeType"]])return NO;
        id hash=f[@"sha256"];if(![hash isKindOfClass:NSString.class] || [hash length]!=64 || [hash rangeOfCharacterFromSet:[NSCharacterSet characterSetWithCharactersInString:@"0123456789abcdef"].invertedSet].location!=NSNotFound)return NO;
    }
    return YES;
}
@interface AstorDockQueue ()
@property(nonatomic) NSURL *directory;
@property(nonatomic) NSMutableArray<NSMutableDictionary *> *records, *sessionRecords;
@property(nonatomic) NSMutableDictionary<NSString *,NSNumber *> *liveCharging;
@property(nonatomic) NSError *storageError;
@end
@implementation AstorDockQueue
- (instancetype)initWithDirectory:(NSURL *)directory {
    if(!(self=[super init]))return nil;
    _directory=directory;_liveCharging=[NSMutableDictionary new];_records=[NSMutableArray new];_sessionRecords=[NSMutableArray new];
    NSError *error=nil;[NSFileManager.defaultManager createDirectoryAtURL:directory withIntermediateDirectories:YES attributes:nil error:&error];
    if(error){_storageError=error;return self;}
    [directory setResourceValue:@YES forKey:NSURLIsExcludedFromBackupKey error:nil];
    NSURL *manifest=[directory URLByAppendingPathComponent:@"queue.json"];
    if([NSFileManager.defaultManager fileExistsAtPath:manifest.path]) {
        NSData *data=[NSData dataWithContentsOfURL:manifest];
        NSDictionary *saved=data?[NSJSONSerialization JSONObjectWithData:data options:NSJSONReadingMutableContainers error:&error]:nil;
        if(![saved isKindOfClass:NSDictionary.class] || ![saved[@"version"] isEqual:@1] || ![saved[@"files"] isKindOfClass:NSArray.class] || ![saved[@"sessions"] isKindOfClass:NSArray.class] || !DockManifest(saved)) {
            _storageError=error?:[NSError errorWithDomain:@"AstorDock" code:1 userInfo:@{NSLocalizedDescriptionKey:@"Архив очереди повреждён. Исходные файлы сохранены."}];return self;
        }
        _records=[saved[@"files"] mutableCopy];_sessionRecords=[saved[@"sessions"] mutableCopy];
        for(NSMutableDictionary *record in _records)if([record[@"state"] isEqual:@"uploading"])record[@"state"]=@"pending";
    }
    [self save];return self;
}
- (NSArray *)files {return [[NSArray alloc]initWithArray:self.records copyItems:YES];}
- (NSArray *)sessions {return [[NSArray alloc]initWithArray:self.sessionRecords copyItems:YES];}
- (BOOL)save {
    if(self.storageError)return NO;
    NSError *error=nil;NSData *data=[NSJSONSerialization dataWithJSONObject:@{@"version":@1,@"files":self.records,@"sessions":self.sessionRecords} options:0 error:&error];
    if(!data || ![data writeToURL:[self.directory URLByAppendingPathComponent:@"queue.json"] options:NSDataWritingAtomic error:&error]){self.storageError=error;return NO;}
#if TARGET_OS_IOS
    [NSFileManager.defaultManager setAttributes:@{NSFileProtectionKey:NSFileProtectionCompleteUntilFirstUserAuthentication} ofItemAtPath:[self.directory URLByAppendingPathComponent:@"queue.json"].path error:nil];
#endif
    return YES;
}
- (NSMutableDictionary *)mutableSession:(NSString *)deviceId {
    for(NSMutableDictionary *s in self.sessionRecords.reverseObjectEnumerator)if([s[@"deviceId"] isEqual:deviceId] && ![s[@"state"] isEqual:@"imported"])return s;
    return nil;
}
- (NSDictionary *)sessionForDevice:(NSString *)deviceId {return [[self mutableSession:deviceId] copy];}
- (BOOL)beginSessionForDevice:(NSString *)deviceId baseline:(NSArray<NSString *> *)names error:(NSError **)error {
    if(self.storageError || !deviceId.length || [self mutableSession:deviceId]) {if(error)*error=self.storageError?:[NSError errorWithDomain:@"AstorDock" code:2 userInfo:@{NSLocalizedDescriptionKey:@"Сначала завершите предыдущую выгрузку."}];return NO;}
    if(!DockNames(names))return NO;
    [self.sessionRecords addObject:[@{@"sessionId":NSUUID.UUID.UUIDString.lowercaseString,@"deviceId":deviceId,@"baseline":names,@"state":@"recording",@"startedAt":@(NSDate.date.timeIntervalSince1970)} mutableCopy]];
    // Ignore any pre-session cached battery value. A fresh charging event arms import.
    [self.liveCharging removeObjectForKey:deviceId];return [self save];
}
- (BOOL)observeChargingForDevice:(NSString *)deviceId component:(NSInteger)component state:(NSInteger)state {
    if(component!=0 || (state!=0 && state!=1))return NO;
    NSNumber *previous=self.liveCharging[deviceId];self.liveCharging[deviceId]=@(state);
    NSMutableDictionary *s=[self mutableSession:deviceId];
    if(state!=1 || [previous isEqual:@1] || ![s[@"state"] isEqual:@"recording"] || self.storageError)return NO;
    s[@"state"]=@"waitingDevice";s[@"endedAt"]=@(NSDate.date.timeIntervalSince1970);
    if([s[@"source"] isEqual:@"phoneCapture"])[self releaseCapturedSession:s];return [self save];
}
- (BOOL)beginCapturedSessionForDevice:(NSString *)deviceId {
    if(![self beginSessionForDevice:deviceId baseline:@[] error:nil])return NO;
    [self mutableSession:deviceId][@"source"]=@"phoneCapture";return [self save];
}
- (void)releaseCapturedSession:(NSMutableDictionary *)s {
    for(NSMutableDictionary *f in self.records)if([f[@"sessionId"] isEqual:s[@"sessionId"]] && [f[@"state"] isEqual:@"held"])f[@"state"]=@"pending";
    s[@"state"]=@"imported";
}
- (BOOL)stageCapturedFile:(NSURL *)source device:(NSString *)deviceId mime:(NSString *)mime error:(NSError **)error {
    NSMutableDictionary *s=[self mutableSession:deviceId];if(![s[@"source"] isEqual:@"phoneCapture"] || ![s[@"state"] isEqual:@"recording"])return NO;
    return [self copyFile:source session:s name:NSUUID.UUID.UUIDString.lowercaseString mime:mime state:@"held" error:error];
}
- (NSArray<NSString *> *)pendingDeviceNames:(NSArray<NSString *> *)inventory device:(NSString *)deviceId {
    NSDictionary *s=[self mutableSession:deviceId];if(!s || [s[@"state"] isEqual:@"recording"])return @[];
    NSMutableSet *known=[NSMutableSet setWithArray:s[@"baseline"]];
    for(NSDictionary *f in self.records)if([f[@"sessionId"] isEqual:s[@"sessionId"]])[known addObject:f[@"deviceFileName"]];
    NSMutableArray *pending=[NSMutableArray new];
    for(NSString *name in inventory)if(![known containsObject:name] && (!s[@"endFiles"] || [s[@"endFiles"] containsObject:name])){[pending addObject:name];[known addObject:name];}
    return pending;
}
- (BOOL)freezeInventory:(NSArray<NSString *> *)inventory device:(NSString *)deviceId {
    NSMutableDictionary *s=[self mutableSession:deviceId];if(!s || [s[@"state"] isEqual:@"recording"] || self.storageError || !DockNames(inventory))return NO;
    if(!s[@"endFiles"]){s[@"endFiles"]=[self pendingDeviceNames:inventory device:deviceId];s[@"inventoryAt"]=@(NSDate.date.timeIntervalSince1970);}
    return [self save];
}
- (BOOL)requestImportForDevice:(NSString *)deviceId {
    NSMutableDictionary *s=[self mutableSession:deviceId];if(!s || self.storageError)return NO;
    if([s[@"state"] isEqual:@"recording"]){s[@"state"]=@"waitingDevice";s[@"endedAt"]=@(NSDate.date.timeIntervalSince1970);s[@"endedBy"]=@"manual";}
    if([s[@"source"] isEqual:@"phoneCapture"])[self releaseCapturedSession:s];
    return [self save];
}
- (BOOL)stageFile:(NSURL *)source device:(NSString *)deviceId name:(NSString *)name mime:(NSString *)mime error:(NSError **)error {
    NSMutableDictionary *s=[self mutableSession:deviceId];
    if(self.storageError || !s || [s[@"state"] isEqual:@"recording"] || [s[@"baseline"] containsObject:name] || (s[@"endFiles"] && ![s[@"endFiles"] containsObject:name]) || !name.length || ![@[@"image/jpeg",@"audio/mp4",@"video/mp4"] containsObject:mime])return NO;
    for(NSDictionary *f in self.records)if([f[@"sessionId"] isEqual:s[@"sessionId"]] && [f[@"deviceFileName"] isEqual:name])return YES;
    return [self copyFile:source session:s name:name mime:mime state:@"pending" error:error];
}
- (BOOL)copyFile:(NSURL *)source session:(NSMutableDictionary *)s name:(NSString *)name mime:(NSString *)mime state:(NSString *)state error:(NSError **)error {
    if(self.storageError || ![@[@"image/jpeg",@"audio/mp4",@"video/mp4"] containsObject:mime])return NO;
    NSDictionary *attrs=[NSFileManager.defaultManager attributesOfItemAtPath:source.path error:error];
    unsigned long long size=[attrs fileSize],used=0;for(NSDictionary *f in self.records)used+=[f[@"size"] unsignedLongLongValue];
    if(!source.isFileURL || ![attrs[NSFileType] isEqual:NSFileTypeRegular] || !size || size>AstorDockFileLimit || used+size>AstorDockQueueLimit){if(error)*error=[NSError errorWithDomain:@"AstorDock" code:3 userInfo:@{NSLocalizedDescriptionKey:@"Недостаточно места в очереди либо файл превышает 64 МБ. Запись остаётся на очках."}];return NO;}
    NSString *fileId=NSUUID.UUID.UUIDString.lowercaseString;NSURL *destination=[self.directory URLByAppendingPathComponent:[fileId stringByAppendingString:@".media"]];
    if(![NSFileManager.defaultManager copyItemAtURL:source toURL:destination error:error])return NO;
    // Hash the private copy, streaming to avoid holding a video in memory.
    NSInputStream *stream=[NSInputStream inputStreamWithURL:destination];[stream open];
    CC_SHA256_CTX digest;CC_SHA256_Init(&digest);uint8_t buffer[65536];NSInteger count;unsigned long long copied=0;
    while((count=[stream read:buffer maxLength:sizeof(buffer)])>0){CC_SHA256_Update(&digest,buffer,(CC_LONG)count);copied+=count;}
    [stream close];if(count<0 || copied!=size){[NSFileManager.defaultManager removeItemAtURL:destination error:nil];return NO;}
    unsigned char bytes[CC_SHA256_DIGEST_LENGTH];CC_SHA256_Final(bytes,&digest);NSMutableString *hash=[NSMutableString new];for(NSUInteger i=0;i<sizeof(bytes);i++)[hash appendFormat:@"%02x",bytes[i]];
    [destination setResourceValue:@YES forKey:NSURLIsExcludedFromBackupKey error:nil];
#if TARGET_OS_IOS
    [NSFileManager.defaultManager setAttributes:@{NSFileProtectionKey:NSFileProtectionCompleteUntilFirstUserAuthentication} ofItemAtPath:destination.path error:nil];
#endif
    NSMutableDictionary *record=[@{@"fileId":fileId,@"sessionId":s[@"sessionId"],@"deviceFileName":name,@"mimeType":mime,@"size":@(size),@"sha256":hash,@"state":state,@"attempts":@0,@"nextAttempt":@0} mutableCopy];
    [self.records addObject:record];return [self save];
}
- (BOOL)finishImportForDevice:(NSString *)deviceId success:(BOOL)success {
    NSMutableDictionary *s=[self mutableSession:deviceId];if(!s || [s[@"state"] isEqual:@"recording"])return NO;
    if(s[@"endFiles"] && [self pendingDeviceNames:s[@"endFiles"] device:deviceId].count)success=NO;
    s[@"state"]=success?@"imported":@"waitingDevice";return [self save] && success;
}
- (NSMutableDictionary *)record:(NSString *)fileId {for(NSMutableDictionary *f in self.records)if([f[@"fileId"] isEqual:fileId])return f;return nil;}
- (NSURL *)URLForFile:(NSDictionary *)file {
    NSString *fileId=file[@"fileId"];if(![fileId isKindOfClass:NSString.class] || ![[NSUUID alloc]initWithUUIDString:fileId])return nil;
    NSURL *url=[self.directory URLByAppendingPathComponent:[fileId stringByAppendingString:@".media"]];return [NSFileManager.defaultManager fileExistsAtPath:url.path]?url:nil;
}
- (NSDictionary *)nextUploadAt:(NSDate *)date {
    if(self.storageError)return nil;
    for(NSDictionary *f in self.records)if([f[@"state"] isEqual:@"pending"] && [f[@"nextAttempt"] doubleValue]<=date.timeIntervalSince1970 && [self URLForFile:f])return [f copy];return nil;
}
- (BOOL)setUploading:(NSString *)fileId {NSMutableDictionary *f=[self record:fileId];if(![f[@"state"] isEqual:@"pending"])return NO;f[@"state"]=@"uploading";return [self save];}
- (BOOL)acceptReceipt:(NSDictionary *)receipt fileId:(NSString *)fileId {
    NSMutableDictionary *f=[self record:fileId];if(!f || ![receipt isKindOfClass:NSDictionary.class] || ![receipt[@"archived"] isEqual:@YES])return NO;
    for(NSString *key in @[@"fileId",@"sessionId",@"sha256",@"size"])if(![receipt[key] isEqual:f[key]])return NO;
    f[@"state"]=@"confirmed";[f removeObjectForKey:@"httpStatus"];return [self save];
}
- (void)failUpload:(NSString *)fileId status:(NSInteger)status at:(NSDate *)date {
    NSMutableDictionary *f=[self record:fileId];if(!f || [f[@"state"] isEqual:@"confirmed"])return;
    NSUInteger attempts=[f[@"attempts"] unsignedIntegerValue]+1;f[@"attempts"]=@(attempts);f[@"httpStatus"]=@(status);
    BOOL permanent=(status>=300 && status<400) || (status>=400 && status<500 && status!=408 && status!=429);
    f[@"state"]=(permanent || attempts>=8)?@"paused":@"pending";
    f[@"nextAttempt"]=@(date.timeIntervalSince1970+MIN(1800,pow(2,MIN(attempts,10UL))*15));[self save];
}
- (void)retryUploads {for(NSMutableDictionary *f in self.records)if([f[@"state"] isEqual:@"pending"] || [f[@"state"] isEqual:@"paused"]){f[@"state"]=@"pending";f[@"nextAttempt"]=@0;f[@"attempts"]=@0;}[self save];}
- (NSString *)summaryForDevice:(NSString *)deviceId {
    if(self.storageError)return @"Архив · ошибка сохранения очереди";
    NSUInteger confirmed=0,pending=0,paused=0;for(NSDictionary *f in self.records){if([f[@"state"] isEqual:@"confirmed"])confirmed++;else {pending++;if([f[@"state"] isEqual:@"paused"])paused++;}}
    NSDictionary *s=deviceId?[self mutableSession:deviceId]:nil;
    NSString *phase=[s[@"state"] isEqual:@"recording"]?@"сессия подготовлена":s?@"ожидаем импорт с очков":@"подготовьте сессию до съёмки";
    return [NSString stringWithFormat:@"Архив · %@\nНа сервере: %lu · в очереди: %lu%@",phase,(unsigned long)confirmed,(unsigned long)pending,paused?@" · требуется повтор":@""];
}
@end
