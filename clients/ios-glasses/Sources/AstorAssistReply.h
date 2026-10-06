#import <Foundation/Foundation.h>
static inline BOOL AstorAssistReplyMatches(id reply,NSString *requestId) {
    if(![requestId isKindOfClass:NSString.class] || ![reply isKindOfClass:NSDictionary.class] || ![reply[@"requestId"] isKindOfClass:NSString.class] || ![reply[@"text"] isKindOfClass:NSString.class])return NO;
    NSUUID *expected=[[NSUUID alloc]initWithUUIDString:requestId],*received=[[NSUUID alloc]initWithUUIDString:reply[@"requestId"]];
    NSString *text=reply[@"text"];
    return expected && [expected isEqual:received] && text.length<=12000 && [text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].length>0;
}
