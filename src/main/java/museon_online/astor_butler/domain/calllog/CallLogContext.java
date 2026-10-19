package museon_online.astor_butler.domain.calllog;

import java.util.function.Supplier;

/**
 * Who a model call is made for: the channel, the raw guest id (pseudonymized only when written) and the correlation id
 * of the guest message being handled. Opened by the message gateway around one message; a call made outside any
 * message (warmup, glasses, ops, indexing) has none and is logged with source {@code internal}.
 */
public final class CallLogContext {

    /** The caller of the current message. */
    public record Caller(String source, String rawUserId, String correlationId) {
    }

    /** Restores the previous caller; never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private CallLogContext() {
    }

    public static Caller current() {
        return CURRENT.get();
    }

    public static Scope open(String source, String rawUserId, String correlationId) {
        Caller previous = CURRENT.get();
        CURRENT.set(new Caller(source, rawUserId, correlationId));
        return () -> restore(previous);
    }

    /** The supplier runs with the caller of the thread that built it, e.g. across {@code CompletableFuture.supplyAsync}. */
    public static <T> Supplier<T> propagate(Supplier<T> supplier) {
        Caller captured = CURRENT.get();
        return () -> {
            Caller previous = CURRENT.get();
            restore(captured);
            try {
                return supplier.get();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(Caller caller) {
        if (caller == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(caller);
        }
    }
}
