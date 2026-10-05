#import <Foundation/Foundation.h>
static inline BOOL AstorIsWakeWord(id word) {
    if(![word isKindOfClass:NSString.class])return NO;
    NSString *clean=[[word lowercaseString] stringByTrimmingCharactersInSet:NSCharacterSet.punctuationCharacterSet];
    return [clean isEqual:@"астор"] || [clean isEqual:@"astor"];
}
