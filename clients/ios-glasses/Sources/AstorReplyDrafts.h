#import <Foundation/Foundation.h>

/*
 What the staff member said in answer to a message, kept as a draft until they send it themselves.

 Astor never sends it. The phone holds the recognized text, the staff member reads it when they next
 look at the screen, and sending happens in their own messenger, from their own account. A draft that
 was never sent simply expires, and the restaurant sees an unanswered message as unanswered.
 */
static const NSTimeInterval AstorReplyDraftTTL = 12 * 60 * 60;

@interface AstorReplyDraft : NSObject
@property(nonatomic,readonly) NSString *draftId;      // canonical lowercase UUID, ours
@property(nonatomic,readonly) NSString *messageId;    // the message being answered, or nil for a free note
@property(nonatomic,readonly) NSString *messageText;  // what was asked, for the staff member to see
@property(nonatomic,readonly) NSString *text;         // what the recognizer heard
@property(nonatomic,readonly) NSDate *createdAt;
+ (instancetype)answering:(NSString *)messageId asked:(NSString *)messageText text:(NSString *)text at:(NSDate *)date;
@end

@interface AstorReplyDrafts : NSObject
/** Newest first. At most 20 are kept; older than AstorReplyDraftTTL are dropped. */
- (BOOL)add:(AstorReplyDraft *)draft;
- (NSArray<AstorReplyDraft *> *)draftsAt:(NSDate *)date;
- (AstorReplyDraft *)firstAt:(NSDate *)date;
- (BOOL)markSent:(NSString *)draftId;
- (BOOL)discard:(NSString *)draftId;
- (NSUInteger)countAt:(NSDate *)date;
- (NSString *)statusAt:(NSDate *)date;
/** The text the staff member will see prefilled in their messenger; never sent from here. */
+ (NSString *)shareTextFor:(AstorReplyDraft *)draft;
+ (NSURL *)telegramShareURLFor:(AstorReplyDraft *)draft;
@end
