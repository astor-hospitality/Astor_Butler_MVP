#import "AstorLunchGuide.h"

@interface AstorLunchGuide ()
@property(nonatomic,readwrite) BOOL active, finished;
@property(nonatomic,readwrite) NSUInteger stepIndex, revision;
@property(nonatomic,readwrite) NSString *sessionId;
@property(nonatomic,strong) NSMutableDictionary *receipts;
@end

@implementation AstorLunchGuide
+ (NSArray<NSDictionary<NSString *,NSString *> *> *)steps {
    return @[
        @{@"title":@"Подготовить стол",@"stageCode":@"TABLE_PREPARE",@"hint":@"Проверьте чистоту стола, два свободных места и удобный проход для гостей. Фото можно сделать при необходимости.",@"shortHint":@"подготовьте чистый стол на двоих"},
        @{@"title":@"Накрыть на двоих",@"stageCode":@"PLACE_SETTINGS",@"hint":@"Подготовьте два комплекта приборов, две салфетки и два бокала. Направьте очки на весь стол и сделайте фото для проверки.",@"shortHint":@"положите два комплекта приборов, салфеток и бокалов. Затем снимите сервировку"},
        @{@"title":@"Вода и меню",@"stageCode":@"WATER_MENU",@"hint":@"Подготовьте меню и воду по стандарту ресторана. Пожелания гостей уточните при встрече. Фото можно сделать при необходимости.",@"shortHint":@"подготовьте воду и меню"},
        @{@"title":@"Проверить перед встречей",@"stageCode":@"FINAL_CHECK",@"hint":@"Посмотрите на стол целиком: два места готовы, сервировка аккуратна. Снимите финальный вид стола; замечания Butler проверьте сами.",@"shortHint":@"снимите финальный вид стола и проверьте замечания перед встречей гостей"}
    ];
}
- (void)start {self.active=YES;self.finished=NO;self.stepIndex=0;self.revision=1;self.sessionId=NSUUID.UUID.UUIDString.lowercaseString;self.receipts=[NSMutableDictionary new];}
- (BOOL)advance {
    if(!self.canAdvance)return NO;
    if(self.stepIndex+1<self.class.steps.count)self.stepIndex++;
    else {self.active=NO;self.finished=YES;}
    self.revision++;return YES;
}
- (void)stop {self.active=NO;self.finished=NO;self.stepIndex=0;self.revision++;self.sessionId=nil;[self.receipts removeAllObjects];}
- (NSDictionary<NSString *,NSString *> *)step {return self.active?self.class.steps[self.stepIndex]:nil;}
- (NSString *)brief {
    if(self.finished)return @"Все шаги подачи пройдены. Назначенные поручения появятся, когда подключим портал задач.";
    if(!self.active)return @"Подача бизнес-ланча на двоих не начата. Откройте Astor Glasses и нажмите «Начать подачу».";
    return [NSString stringWithFormat:@"Бизнес-ланч на двоих. Шаг %lu из %lu: %@. %@",(unsigned long)self.stepIndex+1,(unsigned long)self.class.steps.count,self.step[@"title"],self.step[@"hint"]];
}
- (NSString *)compactBrief {
    if(self.finished)return @"Подача завершена.";
    if(!self.active)return @"Начните подачу на телефоне.";
    return [NSString stringWithFormat:@"Шаг %lu: %@.",(unsigned long)self.stepIndex+1,self.step[@"shortHint"]];
}
- (NSDictionary *)photoContext {
    if(!self.active)return nil;
    NSString *prompt=[NSString stringWithFormat:@"Сервировка бизнес-ланча для двух гостей. Текущий шаг: %@. Подсказка: %@. Рассмотри только приложенное фото. Ответь кратко по-русски: что видно и что стоит проверить на этом шаге. Если детали неразличимы, скажи об этом. Не определяй личность гостей или номер стола. Не утверждай, что поручение выполнено или сервировка подтверждена: это подсказка сотруднику.",self.step[@"title"],self.step[@"hint"]];
    NSDictionary *wire=@{@"sessionId":self.sessionId,@"scenarioCode":@"BUSINESS_LUNCH_TWO",@"stageCode":self.step[@"stageCode"],@"revision":@(self.revision)};
    return @{@"training":@YES,@"sessionId":self.sessionId,@"revision":@(self.revision),@"stepIndex":@(self.stepIndex),@"wire":wire,@"prompt":prompt};
}
- (BOOL)acceptsPhotoContext:(NSDictionary *)context {
    return [context isKindOfClass:NSDictionary.class] && self.active && [context[@"training"] isEqual:@YES] && [context[@"sessionId"] isEqual:self.sessionId] && [context[@"revision"] isEqual:@(self.revision)] && [context[@"stepIndex"] isEqual:@(self.stepIndex)] && [context[@"wire"] isEqual:self.photoContext[@"wire"]];
}
- (BOOL)photoRequired {return self.active && (self.stepIndex==1 || self.stepIndex==3);}
- (BOOL)photoReceived {return self.active && self.receipts[self.step[@"stageCode"]]!=nil;}
- (BOOL)canAdvance {return self.active && (!self.photoRequired || self.photoReceived);}
- (NSUInteger)photoCount {return self.receipts.count;}
- (NSString *)photoStatus {
    if(!self.active)return [NSString stringWithFormat:@"Фото сохранено на сервере: %lu",(unsigned long)self.photoCount];
    return self.photoReceived?@"Фото этого шага сохранено на сервере. Проверьте ответ и перейдите дальше.":self.photoRequired?@"Нужно фото этого шага. Переход откроется после подтверждения сохранения.":@"Фото необязательно; снимите, если нужна подсказка.";
}
- (BOOL)acceptPhotoReceipt:(NSDictionary *)receipt context:(NSDictionary *)context requestId:(NSString *)requestId {
    if(![self acceptsPhotoContext:context] || ![receipt isKindOfClass:NSDictionary.class] || ![receipt[@"archived"] isEqual:@YES] || ![receipt[@"context"] isEqual:context[@"wire"]])return NO;
    id idValue=receipt[@"requestId"];if(![idValue isKindOfClass:NSString.class] || ![requestId isKindOfClass:NSString.class])return NO;
    NSUUID *expected=[[NSUUID alloc]initWithUUIDString:requestId],*received=[[NSUUID alloc]initWithUUIDString:idValue];
    if(!expected || ![expected isEqual:received])return NO;
    self.receipts[self.step[@"stageCode"]]=@{@"requestId":expected.UUIDString.lowercaseString,@"context":context[@"wire"]};return YES;
}
@end
