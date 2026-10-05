#import "AstorDockUploader.h"
static NSString *const AstorDockBackgroundID=@"com.astor.glasses.media-upload.v1";
@interface AstorDockUploader ()
@property(nonatomic) AstorDockQueue *queue;
@property(nonatomic) NSURLSession *session;
@property(nonatomic) NSURLSession *controlSession;
@property(nonatomic) NSURLSessionDataTask *capabilityTask;
@property(nonatomic) NSURL *baseURL;
@property(nonatomic) NSString *bearer, *activeFile;
@property(nonatomic) NSMutableDictionary<NSNumber *,NSMutableData *> *responses;
@property(nonatomic) NSDate *capabilitiesAt, *nextCapabilityCheck;
@property(nonatomic) NSUInteger maxFileBytes;
@property(nonatomic) BOOL archiveEnabled, restored;
@property(nonatomic) NSUInteger configurationGeneration;
@property(nonatomic,copy) void (^backgroundCompletion)(void);
@property(nonatomic) NSTimer *retryTimer;
@end
@implementation AstorDockUploader
- (instancetype)initWithQueue:(AstorDockQueue *)queue {
    if(!(self=[super init]))return nil;
    _queue=queue;_responses=[NSMutableDictionary new];
    NSURLSessionConfiguration *config=[NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:AstorDockBackgroundID];
    config.sessionSendsLaunchEvents=YES;config.discretionary=NO;config.allowsCellularAccess=YES;config.waitsForConnectivity=YES;config.timeoutIntervalForResource=24*60*60;
    _session=[NSURLSession sessionWithConfiguration:config delegate:self delegateQueue:NSOperationQueue.mainQueue];
    _controlSession=[NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.ephemeralSessionConfiguration delegate:self delegateQueue:NSOperationQueue.mainQueue];
    __weak typeof(self) weak=self;
    [_session getAllTasksWithCompletionHandler:^(NSArray<__kindof NSURLSessionTask *> *tasks){dispatch_async(dispatch_get_main_queue(),^{
        typeof(self) self=weak;if(!self)return;
        for(NSURLSessionTask *task in tasks){
            BOOL matched=NO;for(NSDictionary *file in self.queue.files)if([file[@"fileId"] isEqual:task.taskDescription] && ![file[@"state"] isEqual:@"confirmed"]){matched=YES;break;}
            if(!matched || self.activeFile){[task cancel];continue;}
            self.activeFile=task.taskDescription;[self.queue setUploading:self.activeFile];
            if(task.state==NSURLSessionTaskStateSuspended)[task resume];
        }
        self.restored=YES;[self pump];
    });}];return self;
}
- (void)status:(NSString *)status {if(self.changed)self.changed(status);}
- (void)configureBaseURL:(NSURL *)baseURL bearer:(NSString *)bearer {
    NSURLComponents *parts=baseURL?[NSURLComponents componentsWithURL:baseURL resolvingAgainstBaseURL:NO]:nil;
    if(![parts.scheme.lowercaseString isEqual:@"https"] || !parts.host.length || parts.user.length || parts.password.length || parts.query.length || parts.fragment.length)baseURL=nil;
    if([self.baseURL isEqual:baseURL] && [self.bearer isEqual:bearer])return;
    self.configurationGeneration++;[self.capabilityTask cancel];self.capabilityTask=nil;self.baseURL=baseURL;self.bearer=bearer;
    self.archiveEnabled=NO;self.capabilitiesAt=nil;self.nextCapabilityCheck=nil;
    [self pump];
}
- (NSURL *)URLForPath:(NSString *)suffix {
    NSURLComponents *parts=[NSURLComponents componentsWithURL:self.baseURL resolvingAgainstBaseURL:NO];
    parts.path=[[parts.path stringByTrimmingCharactersInSet:[NSCharacterSet characterSetWithCharactersInString:@"/"]] length]?[[@"/" stringByAppendingString:[parts.path stringByTrimmingCharactersInSet:[NSCharacterSet characterSetWithCharactersInString:@"/"]]] stringByAppendingString:suffix]:suffix;
    return parts.URL;
}
- (void)scheduleAfter:(NSTimeInterval)seconds {
    [self.retryTimer invalidate];__weak typeof(self) weak=self;
    self.retryTimer=[NSTimer scheduledTimerWithTimeInterval:MAX(1,seconds) repeats:NO block:^(NSTimer *timer){[weak pump];}];
}
- (void)pump {
    if(!self.restored || self.activeFile || self.queue.storageError)return;
    NSDictionary *file=[self.queue nextUploadAt:NSDate.date];
    if(!file){BOOL pending=NO;for(NSDictionary *f in self.queue.files)if([f[@"state"] isEqual:@"pending"]){pending=YES;break;}if(pending)[self scheduleAfter:30];return;}
    if(!self.baseURL || !self.bearer.length){[self status:@"Архив · подключите Astor, файлы сохранены на iPhone"];return;}
    if(!self.capabilitiesAt || -self.capabilitiesAt.timeIntervalSinceNow>300){
        if(self.capabilityTask)return;
        if(self.nextCapabilityCheck.timeIntervalSinceNow>0){[self scheduleAfter:self.nextCapabilityCheck.timeIntervalSinceNow];return;}
        NSMutableURLRequest *request=[NSMutableURLRequest requestWithURL:[self URLForPath:@"/api/glasses/capabilities"]];request.timeoutInterval=30;
        [request setValue:[@"Bearer " stringByAppendingString:self.bearer] forHTTPHeaderField:@"Authorization"];
        __weak typeof(self) weak=self;NSUInteger generation=self.configurationGeneration;
        // Capabilities carry no media; use a short foreground request before scheduling background bytes.
        self.capabilityTask=[self.controlSession dataTaskWithRequest:request completionHandler:^(NSData *data,NSURLResponse *response,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
            typeof(self) self=weak;if(!self || generation!=self.configurationGeneration)return;self.capabilityTask=nil;
            NSInteger code=[(NSHTTPURLResponse *)response statusCode];NSDictionary *json=data.length<=16384?[NSJSONSerialization JSONObjectWithData:data options:0 error:nil]:nil;
            NSDictionary *archive=[json isKindOfClass:NSDictionary.class] && [json[@"mediaArchive"] isKindOfClass:NSDictionary.class]?json[@"mediaArchive"]:nil;
            self.archiveEnabled=!error && code==200 && [archive[@"enabled"] isEqual:@YES] && [archive[@"maxFileBytes"] isKindOfClass:NSNumber.class];
            self.maxFileBytes=MIN(64*1024*1024,[archive[@"maxFileBytes"] unsignedIntegerValue]);
            if(self.archiveEnabled){self.capabilitiesAt=NSDate.date;[self pump];}
            else {self.nextCapabilityCheck=[NSDate dateWithTimeIntervalSinceNow:60];[self status:code==401?@"Архив · войдите в Astor, файлы сохранены":@"Архив сервера пока недоступен · файлы сохранены"];[self scheduleAfter:60];}
        });}];[self.capabilityTask resume];return;
    }
    if(!self.archiveEnabled)return;
    if([file[@"size"] unsignedLongLongValue]>self.maxFileBytes){[self.queue failUpload:file[@"fileId"] status:413 at:NSDate.date];[self status:@"Архив · файл превышает лимит сервера, запись сохранена"];[self pump];return;}
    NSURL *source=[self.queue URLForFile:file];if(!source)return;
    NSMutableURLRequest *request=[NSMutableURLRequest requestWithURL:[self URLForPath:@"/api/glasses/media"]];request.HTTPMethod=@"POST";
    [request setValue:[@"Bearer " stringByAppendingString:self.bearer] forHTTPHeaderField:@"Authorization"];
    [request setValue:file[@"mimeType"] forHTTPHeaderField:@"Content-Type"];
    [request setValue:[file[@"size"] stringValue] forHTTPHeaderField:@"Content-Length"];
    [request setValue:file[@"fileId"] forHTTPHeaderField:@"X-Glasses-File-Id"];
    [request setValue:file[@"sessionId"] forHTTPHeaderField:@"X-Glasses-Session-Id"];
    [request setValue:file[@"sha256"] forHTTPHeaderField:@"X-Content-SHA256"];
    if(![self.queue setUploading:file[@"fileId"]])return;
    NSURLSessionUploadTask *task=[self.session uploadTaskWithRequest:request fromFile:source];task.taskDescription=file[@"fileId"];self.activeFile=task.taskDescription;
    [self status:@"Архив · отправляем запись в Astor"];[task resume];
}
- (void)retry {self.capabilitiesAt=nil;self.nextCapabilityCheck=nil;[self.queue retryUploads];[self pump];}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveData:(NSData *)data {
    if(session!=self.session)return;
    NSMutableData *response=self.responses[@(task.taskIdentifier)];if(!response){response=[NSMutableData new];self.responses[@(task.taskIdentifier)]=response;}
    if(response.length+data.length>16384){[task cancel];return;}[response appendData:data];
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task willPerformHTTPRedirection:(NSHTTPURLResponse *)response newRequest:(NSURLRequest *)request completionHandler:(void (^)(NSURLRequest *))completion {completion(nil);}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    if(session!=self.session)return;
    NSData *data=self.responses[@(task.taskIdentifier)];[self.responses removeObjectForKey:@(task.taskIdentifier)];
    NSInteger code=[(NSHTTPURLResponse *)task.response statusCode];id receipt=data?[NSJSONSerialization JSONObjectWithData:data options:0 error:nil]:nil;
    BOOL confirmed=!error && code>=200 && code<300 && [self.queue acceptReceipt:receipt fileId:task.taskDescription];
    if(!confirmed)[self.queue failUpload:task.taskDescription status:error?0:((code>=200 && code<300)?422:code) at:NSDate.date];
    if([self.activeFile isEqual:task.taskDescription])self.activeFile=nil;
    [self status:confirmed?@"Архив · сервер подтвердил сохранение записи":@"Архив · нет подтверждения, запись остаётся на iPhone"];[self pump];
}
- (BOOL)handleBackgroundSession:(NSString *)identifier completion:(void (^)(void))completion {
    if(![identifier isEqual:AstorDockBackgroundID])return NO;self.backgroundCompletion=completion;return YES;
}
- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session {
    void (^completion)(void)=self.backgroundCompletion;self.backgroundCompletion=nil;if(completion)dispatch_async(dispatch_get_main_queue(),completion);
}
@end
