package museon_online.astor_butler.api.glasses.tasks;

/**
 * Who is calling, as established by the server from the credential.
 * Never built from request bodies: a client-supplied tenant or staff id is not authorization.
 */
public record StaffScope(String tenant, String staffId, Role role) {

    public enum Role { WAITER, HOSTESS, MANAGER }

    /** Hostess and manager may create, reassign and cancel tasks of their venue. */
    boolean manages() {
        return role == Role.HOSTESS || role == Role.MANAGER;
    }
}
