#import "../Sources/AstorRecordingGuard.h"
#import <assert.h>

int main(void) { @autoreleasepool {
    NSDate *t0=[NSDate dateWithTimeIntervalSince1970:1760000000];
    NSDate *(^at)(NSTimeInterval) = ^(NSTimeInterval s){ return [t0 dateByAddingTimeInterval:s]; };

    // A stop that follows the start within the guard is the same tap echoed, not the staff member's wish.
    assert(AstorRecordingStopIsTooEarly(t0, at(0.05)));
    assert(AstorRecordingStopIsTooEarly(t0, at(1.19)));
    // After the guard a stop is a stop.
    assert(!AstorRecordingStopIsTooEarly(t0, at(1.2)));
    assert(!AstorRecordingStopIsTooEarly(t0, at(5)));
    // No start on record: nothing to guard, so nothing is swallowed.
    assert(!AstorRecordingStopIsTooEarly(nil, at(0.1)));
    assert(!AstorRecordingStopIsTooEarly(t0, nil));
    // A repeated start inside the guard is ignored the same way.
    assert(AstorRecordingStartIsRepeated(t0, at(0.3)));
    assert(!AstorRecordingStartIsRepeated(t0, at(2)));

    printf("recording-guard: OK\n");
    return 0;
} }
