#import "../Sources/AstorReplyDrafts.h"
#import <assert.h>

int main(void) { @autoreleasepool {
    NSDate *t0=[NSDate dateWithTimeIntervalSince1970:1760000000];
    NSDate *(^at)(NSTimeInterval) = ^(NSTimeInterval s){ return [t0 dateByAddingTimeInterval:s]; };
    NSString *one=@"80d26cf1-5139-4121-a4ca-dfb14aac225c", *two=@"ff5a8c58-bb60-43f4-b542-1e26c8b96581";

    // A draft needs words; a bad message id is refused rather than silently answered to nobody.
    assert([AstorReplyDraft answering:one asked:@"Стол пять просит счёт." text:@"  " at:t0]==nil);
    assert([AstorReplyDraft answering:@"nope" asked:@"x" text:@"Передайте, иду" at:t0]==nil);
    assert([AstorReplyDraft answering:nil asked:nil text:@"Свободная заметка" at:t0]!=nil);

    AstorReplyDrafts *drafts=[AstorReplyDrafts new];
    AstorReplyDraft *first=[AstorReplyDraft answering:one asked:@"Стол пять просит счёт." text:@"  Иду, две минуты  " at:t0];
    assert([first.text isEqual:@"Иду, две минуты"]);
    assert([first.messageId isEqual:one]);
    assert([drafts add:first]);
    assert([drafts countAt:t0]==1);
    assert([[drafts statusAt:t0] containsString:@"Иду, две минуты"]);

    // A second answer to the same message replaces the first: the staff member never sends two.
    AstorReplyDraft *corrected=[AstorReplyDraft answering:one asked:@"Стол пять просит счёт." text:@"Уже несу" at:at(30)];
    assert([drafts add:corrected]);
    assert([drafts countAt:at(30)]==1);
    assert([[drafts firstAt:at(30)].text isEqual:@"Уже несу"]);

    // A different message keeps its own draft, newest first.
    assert([drafts add:[AstorReplyDraft answering:two asked:@"Гость спрашивал про парковку." text:@"Сказал про двор" at:at(60)]]);
    assert([drafts countAt:at(60)]==2);
    assert([[drafts firstAt:at(60)].messageId isEqual:two]);

    // The share text carries what was asked, and nothing is sent from here.
    NSString *share=[AstorReplyDrafts shareTextFor:[drafts firstAt:at(60)]];
    assert([share isEqual:@"На «Гость спрашивал про парковку.»: Сказал про двор"]);
    NSURL *url=[AstorReplyDrafts telegramShareURLFor:[drafts firstAt:at(60)]];
    assert([url.scheme isEqual:@"tg"]);
    assert([url.absoluteString containsString:@"msg?text="]);
    assert(![url.absoluteString containsString:@" "]);

    // Sending or discarding removes it; an unknown id changes nothing.
    assert([drafts markSent:[drafts firstAt:at(60)].draftId]);
    assert([drafts countAt:at(60)]==1);
    assert(![drafts discard:@"unknown"]);
    assert([drafts countAt:at(60)]==1);

    // A draft nobody sent expires instead of waiting forever.
    assert([drafts countAt:at(AstorReplyDraftTTL + 61)]==0);
    assert([[drafts statusAt:at(AstorReplyDraftTTL + 61)] isEqual:@"Черновиков ответа нет"]);

    // The list is bounded.
    AstorReplyDrafts *many=[AstorReplyDrafts new];
    for(int i=0;i<25;i++)[many add:[AstorReplyDraft answering:nil asked:nil text:[NSString stringWithFormat:@"Заметка %d",i] at:t0]];
    assert([many countAt:t0]==20);

    printf("reply-drafts: OK\n");
    return 0;
} }
