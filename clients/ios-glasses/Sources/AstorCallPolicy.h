#import <Foundation/Foundation.h>
// SCO used by our recorder can be reported as InCall by the accessory firmware.
// A CallKit call and ringing still interrupt immediately, including during our audio.
static inline BOOL AstorCallShouldInterrupt(BOOL systemCall,BOOL ringing,BOOL sdkInCall,BOOL ownHFP) {
    return systemCall || ringing || (sdkInCall && !ownHFP);
}
