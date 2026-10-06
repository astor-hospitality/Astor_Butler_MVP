#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN
// Main-thread confined. The manifest contains metadata only, never credentials.
@interface AstorDockQueue : NSObject
@property(nonatomic,readonly) NSURL *directory;
@property(nonatomic,readonly) NSArray<NSDictionary *> *files;
@property(nonatomic,readonly) NSArray<NSDictionary *> *sessions;
@property(nonatomic,readonly,nullable) NSError *storageError;
- (instancetype)initWithDirectory:(NSURL *)directory;
- (nullable NSDictionary *)sessionForDevice:(NSString *)deviceId;
- (BOOL)beginSessionForDevice:(NSString *)deviceId baseline:(NSArray<NSString *> *)names error:(NSError **)error;
- (BOOL)beginCapturedSessionForDevice:(NSString *)deviceId;
- (BOOL)stageCapturedFile:(NSURL *)source device:(NSString *)deviceId mime:(NSString *)mime error:(NSError **)error;
// Only live battery callbacks may call this. Case charging is not a trigger.
- (BOOL)observeChargingForDevice:(NSString *)deviceId component:(NSInteger)component state:(NSInteger)state;
- (BOOL)requestImportForDevice:(NSString *)deviceId;
- (BOOL)freezeInventory:(NSArray<NSString *> *)inventory device:(NSString *)deviceId;
- (NSArray<NSString *> *)pendingDeviceNames:(NSArray<NSString *> *)inventory device:(NSString *)deviceId;
- (BOOL)stageFile:(NSURL *)source device:(NSString *)deviceId name:(NSString *)name mime:(NSString *)mime error:(NSError **)error;
- (BOOL)finishImportForDevice:(NSString *)deviceId success:(BOOL)success;
- (nullable NSDictionary *)nextUploadAt:(NSDate *)date;
- (nullable NSURL *)URLForFile:(NSDictionary *)file;
- (BOOL)setUploading:(NSString *)fileId;
- (BOOL)acceptReceipt:(NSDictionary *)receipt fileId:(NSString *)fileId;
- (void)failUpload:(NSString *)fileId status:(NSInteger)status at:(NSDate *)date;
- (void)retryUploads;
- (NSString *)summaryForDevice:(nullable NSString *)deviceId;
@end
NS_ASSUME_NONNULL_END
