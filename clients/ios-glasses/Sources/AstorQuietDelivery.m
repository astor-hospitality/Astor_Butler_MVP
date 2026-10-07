#import "AstorQuietDelivery.h"
#import <math.h>

@interface AstorQuietDeliveryMessage ()
@property(nonatomic,readwrite) NSString *messageId, *text;
@property(nonatomic,readwrite) NSDate *receivedAt;
@end

@implementation AstorQuietDeliveryMessage
+ (instancetype)withId:(NSString *)messageId text:(NSString *)text at:(NSDate *)date {
    NSUUID *uuid=[[NSUUID alloc]initWithUUIDString:messageId?:@""];
    NSString *trimmed=[(text?:@"") stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if(!uuid || !trimmed.length || trimmed.length>600 || !date)return nil;
    AstorQuietDeliveryMessage *message=[self new];
    message.messageId=uuid.UUIDString.lowercaseString;
    message.text=trimmed;
    message.receivedAt=date;
    return message;
}
@end

@implementation AstorQuietDelivery {
    NSMutableArray<AstorQuietDeliveryMessage *> *_queue;
    NSMutableSet<NSString *> *_known;          // every id ever queued, so a repeated poll does not speak twice
    AstorQuietDeliveryMessage *_speaking;
    NSDate *_quietSince;                        // when the last speech or our own audio ended
    NSString *_status;
}

static const NSUInteger AstorQuietDeliveryQueueLimit = 20;
static const NSUInteger AstorQuietDeliveryKnownLimit = 400;

- (instancetype)init {
    if((self=[super init])){
        _queue=[NSMutableArray new];
        _known=[NSMutableSet new];
        _status=@"Сообщений нет";
    }
    return self;
}

- (BOOL)enqueue:(AstorQuietDeliveryMessage *)message {
    if(!message || [_known containsObject:message.messageId])return NO;
    if(_queue.count>=AstorQuietDeliveryQueueLimit)return NO;
    [_known addObject:message.messageId];
    if(_known.count>AstorQuietDeliveryKnownLimit)[_known removeAllObjects];
    [_queue addObject:message];
    return YES;
}

- (NSUInteger)waiting { return _queue.count; }
- (NSString *)status { return _status; }

- (AstorQuietDeliveryMessage *)nextAt:(NSDate *)date state:(AstorQuietDeliveryState)state {
    if(_speaking){_status=@"Читаю сообщение";return nil;}
    BOOL busy=state.speechNearby || state.ownAudioActive || state.callActive || state.musicActive;
    if(busy){
        _quietSince=nil;
        if(!_queue.count){_status=@"Сообщений нет";return nil;}
        _status=state.callActive?[NSString stringWithFormat:@"Отложено на время звонка: %lu",(unsigned long)_queue.count]
                               :[NSString stringWithFormat:@"Отложено до паузы в разговоре: %lu",(unsigned long)_queue.count];
        return nil;
    }
    if(!_quietSince)_quietSince=date;
    if(!_queue.count){_status=@"Сообщений нет";return nil;}
    if(!state.glassesReady){_status=[NSString stringWithFormat:@"Ждёт звук очков: %lu",(unsigned long)_queue.count];return nil;}
    NSTimeInterval quiet=[date timeIntervalSinceDate:_quietSince];
    if(quiet<AstorQuietDeliveryDelay){
        _status=[NSString stringWithFormat:@"Прочитаю через %.0f с: %lu",ceil(AstorQuietDeliveryDelay-quiet),(unsigned long)_queue.count];
        return nil;
    }
    return _queue.firstObject;
}

- (void)startedSpeaking:(AstorQuietDeliveryMessage *)message at:(NSDate *)date {
    if(!message || _speaking || _queue.firstObject!=message)return;
    [_queue removeObjectAtIndex:0];
    _speaking=message;
    _quietSince=nil;
    _status=@"Читаю сообщение";
}

- (void)finishedSpeaking:(AstorQuietDeliveryMessage *)message at:(NSDate *)date delivered:(BOOL)delivered {
    if(_speaking!=message)return;
    _speaking=nil;
    // An interrupted message is not lost: it goes back to the front and waits for the next pause.
    if(!delivered && _queue.count<AstorQuietDeliveryQueueLimit)[_queue insertObject:message atIndex:0];
    _quietSince=date;
    _status=_queue.count?[NSString stringWithFormat:@"Отложено до паузы в разговоре: %lu",(unsigned long)_queue.count]:@"Сообщений нет";
}

- (void)reset {
    [_queue removeAllObjects];
    _speaking=nil;
    _quietSince=nil;
    _status=@"Сообщений нет";
}
@end
