#import <Foundation/Foundation.h>
#import "../Sources/AstorGesturePolicy.h"
static void check(BOOL condition,NSString *message){if(!condition){NSLog(@"FAIL: %@",message);exit(1);}}
int main(void){@autoreleasepool{
    check(AstorGestureProfile(@{}).count==0,@"Empty mappings must never lead to writes");
    NSDictionary *reported=@{@1:@10,@2:@10,@7:@0,@8:@0,@18:@3,@19:@4,@24:@10,@25:@12,@5:@1,@34:@5,@35:@6};
    NSDictionary *profile=AstorGestureProfile(reported);
    check([profile isEqualToDictionary:@{@18:@4,@19:@7,@5:@3}],@"Prefer touch doubles; use one gesture per action");
    for(NSNumber *op in @[@1,@2,@7,@8,@34,@35])check(!profile[op],@"Preserve single camera, hold and volume swipe");
    check([AstorGestureProfile(@{@25:@12}) isEqualToDictionary:@{@25:@7}],@"Use only actually reported fallback button");
    NSDictionary *backup=AstorGestureBackup(@{},reported,profile);
    check([backup isEqualToDictionary:@{@"18":@3,@"19":@4,@"5":@1}],@"Back up original values only for affected keys");
    NSDictionary *second=AstorGestureBackup(backup,@{@19:@7,@3:@10},@{@19:@10,@3:@4,@99:@7});
    check([second[@"19"] isEqual:@4] && [second[@"3"] isEqual:@10] && !second[@"99"],@"Repeated edits preserve originals; unknown gestures are never backed up/written");
    puts("Gesture policy: 6 checks passed");return 0;
}}
