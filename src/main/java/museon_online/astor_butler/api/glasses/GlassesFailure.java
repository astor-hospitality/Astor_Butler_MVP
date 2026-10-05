package museon_online.astor_butler.api.glasses;

final class GlassesFailure extends RuntimeException {
    final int status;
    final String code;

    GlassesFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
