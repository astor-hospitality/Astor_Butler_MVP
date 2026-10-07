#import "AstorReplyDrafts.h"

@interface AstorReplyDraft ()
@property(nonatomic,readwrite) NSString *draftId, *messageId, *messageText, *text;
@property(nonatomic,readwrite) NSDate *createdAt, *handedOverAt;
@end

@implementation AstorReplyDraft
+ (instancetype)answering:(NSString *)messageId asked:(NSString *)messageText text:(NSString *)text at:(NSDate *)date {
    NSString *trimmed=[(text?:@"") stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if(!trimmed.length || trimmed.length>1000 || !date)return nil;
    if(messageId && ![[NSUUID alloc]initWithUUIDString:messageId])return nil;
    AstorReplyDraft *draft=[self new];
    draft.draftId=NSUUID.UUID.UUIDString.lowercaseString;
    draft.messageId=messageId.lowercaseString;
    draft.messageText=messageText;
    draft.text=trimmed;
    draft.createdAt=date;
    return draft;
}
@end

@implementation AstorReplyDrafts {
    NSMutableArray<AstorReplyDraft *> *_drafts;
}

static const NSUInteger AstorReplyDraftLimit = 20;

- (instancetype)init {
    if((self=[super init]))_drafts=[NSMutableArray new];
    return self;
}

- (BOOL)add:(AstorReplyDraft *)draft {
    if(!draft)return NO;
    [self expireAt:draft.createdAt];
    // One draft per message: a second answer replaces the first, so the staff member never sends two.
    if(draft.messageId)[_drafts filterUsingPredicate:[NSPredicate predicateWithBlock:^BOOL(AstorReplyDraft *existing,NSDictionary *bindings){
        return ![existing.messageId isEqual:draft.messageId];
    }]];
    [_drafts insertObject:draft atIndex:0];
    while(_drafts.count>AstorReplyDraftLimit)[_drafts removeLastObject];
    return YES;
}

- (NSArray<AstorReplyDraft *> *)draftsAt:(NSDate *)date {
    [self expireAt:date];
    return [_drafts copy];
}

- (AstorReplyDraft *)firstAt:(NSDate *)date {
    [self expireAt:date];
    return _drafts.firstObject;
}

- (BOOL)markHandedOver:(NSString *)draftId at:(NSDate *)date {
    for(AstorReplyDraft *draft in _drafts)if([draft.draftId isEqual:draftId]){draft.handedOverAt=date;return YES;}
    return NO;
}

- (BOOL)markSent:(NSString *)draftId { return [self discard:draftId]; }

- (BOOL)discard:(NSString *)draftId {
    NSUInteger before=_drafts.count;
    [_drafts filterUsingPredicate:[NSPredicate predicateWithBlock:^BOOL(AstorReplyDraft *draft,NSDictionary *bindings){
        return ![draft.draftId isEqual:draftId];
    }]];
    return _drafts.count<before;
}

- (NSUInteger)countAt:(NSDate *)date {
    [self expireAt:date];
    return _drafts.count;
}

- (NSString *)statusAt:(NSDate *)date {
    NSUInteger count=[self countAt:date];
    if(!count)return @"Черновиков ответа нет";
    AstorReplyDraft *first=_drafts.firstObject;
    if(first.handedOverAt)return [NSString stringWithFormat:@"Открыт в Telegram, отправка не подтверждена (%lu): %@",(unsigned long)count,first.text];
    return [NSString stringWithFormat:@"Черновик ответа (%lu): %@",(unsigned long)count,first.text];
}

+ (NSString *)shareTextFor:(AstorReplyDraft *)draft {
    if(!draft)return @"";
    if(draft.messageText.length)return [NSString stringWithFormat:@"На «%@»: %@",draft.messageText,draft.text];
    return draft.text;
}

+ (NSURL *)telegramShareURLFor:(AstorReplyDraft *)draft {
    NSString *text=[self shareTextFor:draft];
    if(!text.length)return nil;
    NSCharacterSet *allowed=[NSCharacterSet characterSetWithCharactersInString:
        @"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"];
    NSString *encoded=[text stringByAddingPercentEncodingWithAllowedCharacters:allowed];
    // Telegram opens its own chooser with the text prefilled; the staff member picks the chat and taps send.
    return [NSURL URLWithString:[@"tg://msg?text=" stringByAppendingString:encoded?:@""]];
}

- (void)expireAt:(NSDate *)date {
    if(!date)return;
    [_drafts filterUsingPredicate:[NSPredicate predicateWithBlock:^BOOL(AstorReplyDraft *draft,NSDictionary *bindings){
        return [date timeIntervalSinceDate:draft.createdAt]<AstorReplyDraftTTL;
    }]];
}
@end
