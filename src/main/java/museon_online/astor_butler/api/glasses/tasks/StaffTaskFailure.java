package museon_online.astor_butler.api.glasses.tasks;

/** A refused request with the HTTP status and stable code a transport should return. */
public final class StaffTaskFailure extends RuntimeException {
    public final int status;
    public final String code;

    StaffTaskFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
