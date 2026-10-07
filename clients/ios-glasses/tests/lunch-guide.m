#import <Foundation/Foundation.h>
#import "../Sources/AstorLunchGuide.h"
#import "../Sources/AstorAssistReply.h"
static void check(BOOL condition,NSString *message){if(!condition){NSLog(@"FAIL: %@",message);exit(1);}}
int main(void){@autoreleasepool{
    AstorLunchGuide *guide=[AstorLunchGuide new];
    check(!guide.active && ![guide advance] && !guide.photoContext,@"No implicit start or photo context");
    [guide start];NSDictionary *first=guide.photoContext;
    check([guide acceptsPhotoContext:first] && [guide.brief containsString:@"Шаг 1 из"],@"Each spoken brief names which step of how many it is");
    check([first[@"prompt"] containsString:@"Не утверждай"],@"The photo prompt forbids claiming an assignment is done");
    check(!first[@"taskId"] && !first[@"tableId"] && !first[@"stageId"],@"A step must never invent portal identifiers");
    [guide advance];
    check(![guide acceptsPhotoContext:first] && [guide acceptsPhotoContext:guide.photoContext],@"A delayed capture cannot belong to a new step");
    NSDictionary *second=guide.photoContext;[guide stop];[guide start];
    check(![guide acceptsPhotoContext:second] && ![guide acceptsPhotoContext:first],@"Restart invalidates even a capture of the same step index");
    for(NSUInteger i=0;i<AstorLunchGuide.steps.count;i++){
        // The brief is a checklist step, never a claim that a portal assigned it or confirmed it.
        check(guide.active && guide.stepIndex==i && [guide.photoContext[@"prompt"] length]<4000,@"Each step stays within the informational request contract");
        check([guide.brief containsString:[NSString stringWithFormat:@"Шаг %lu из %lu",(unsigned long)i+1,(unsigned long)AstorLunchGuide.steps.count]],@"Every spoken brief says which step it is");
        check([guide.compactBrief containsString:[NSString stringWithFormat:@"Шаг %lu",(unsigned long)i+1]],@"The short brief in the glasses also names the step");
        for(NSString *claim in @[@"поручение выполнено",@"задача принята",@"подтверждено"])
            check(![guide.brief.lowercaseString containsString:claim],@"A brief must not claim an assignment or a confirmation");
        if(guide.photoRequired){
            NSDictionary *context=guide.photoContext;NSString *request=NSUUID.UUID.UUIDString;
            check(!guide.canAdvance && ![guide advance],@"Required photos prevent manual advance until a server receipt");
            NSDictionary *receipt=@{@"requestId":request.lowercaseString,@"archived":@YES,@"context":context[@"wire"]};
            check(![guide acceptPhotoReceipt:receipt context:first requestId:request],@"A previous session cannot accept a late receipt");
            check(![guide acceptPhotoReceipt:receipt context:context requestId:NSUUID.UUID.UUIDString],@"Receipt UUID must match this upload");
            check(![guide acceptPhotoReceipt:@{@"requestId":request,@"archived":@NO,@"context":context[@"wire"]} context:context requestId:request],@"Analysis without storage cannot close a photo step");
            check(![guide acceptPhotoReceipt:@{@"requestId":request,@"archived":@YES,@"context":@{}} context:context requestId:request],@"Receipt step/session must match exactly");
            check([guide acceptPhotoReceipt:receipt context:context requestId:request] && guide.canAdvance,@"A matching archived receipt enables explicit advance");
        }
        [guide advance];
    }
    check(guide.photoCount==2,@"Two required photo checkpoints are collected separately");
    check(guide.finished && !guide.active && !guide.photoContext && ![guide advance],@"Finishing the steps does not leave a live task or capture context");
    check([guide.brief containsString:@"портал"],@"When the steps are done the brief says real assignments wait for the portal");
    [guide stop];check(!guide.finished && !guide.active,@"Stop clears local step state");
    NSString *id1=NSUUID.UUID.UUIDString,*id2=NSUUID.UUID.UUIDString;
    check(AstorAssistReplyMatches(@{@"requestId":id1.lowercaseString,@"text":@"Ответ"},id1),@"UUID comparison accepts server canonical casing");
    check(!AstorAssistReplyMatches(@{@"requestId":id2,@"text":@"Чужой ответ"},id1),@"An unrelated response cannot replace the current answer");
    for(id malformed in @[@{},@[],@{@"requestId":id1,@"text":@" \n"},@{@"requestId":@42,@"text":@"Ответ"},@{@"requestId":id1,@"text":@42},@{@"requestId":@"invalid",@"text":@"Ответ"}])
        check(!AstorAssistReplyMatches(malformed,id1),@"Malformed or empty responses must be rejected safely");
    NSISO8601DateFormatter *dates=[NSISO8601DateFormatter new];dates.formatOptions=NSISO8601DateFormatWithInternetDateTime|NSISO8601DateFormatWithFractionalSeconds;
    check([dates dateFromString:@"2026-10-04T22:20:20.923253Z"]!=nil,@"Pilot expiry accepts the server's microsecond ISO format");
    puts("Lunch guide and assist correlation: Photo steps, stale responses and assist correlation: all checks passed");return 0;
}}
