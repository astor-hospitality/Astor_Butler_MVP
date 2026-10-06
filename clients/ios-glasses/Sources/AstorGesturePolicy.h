#import <Foundation/Foundation.h>
// Restrict the default staff profile to reported double/triple presses; leave power,
// single camera presses and volume swipes alone. Device acceptance is checked separately.
static inline NSDictionary<NSNumber *,NSNumber *> *AstorGestureProfile(NSDictionary<NSNumber *,NSNumber *> *reported) {
    NSMutableDictionary *result=[NSMutableDictionary new];
    for(NSNumber *op in @[@19,@25,@4,@31])if(reported[op]){result[op]=@7;break;}
    for(NSNumber *op in @[@18,@24,@3,@30])if(reported[op]){result[op]=@4;break;}
    for(NSNumber *op in @[@5,@6])if(reported[op]){result[op]=@3;break;}
    return result;
}
static inline NSDictionary<NSString *,NSNumber *> *AstorGestureBackup(NSDictionary<NSString *,NSNumber *> *existing,NSDictionary<NSNumber *,NSNumber *> *reported,NSDictionary<NSNumber *,NSNumber *> *changes) {
    NSMutableDictionary *saved=[existing mutableCopy]?:[NSMutableDictionary new];
    for(NSNumber *op in changes)if(reported[op] && !saved[op.stringValue])saved[op.stringValue]=reported[op];
    return saved;
}
