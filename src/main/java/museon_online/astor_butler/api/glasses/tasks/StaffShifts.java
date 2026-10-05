package museon_online.astor_butler.api.glasses.tasks;

/** Tasks are handed out and accepted only while the staff member has an open shift at the venue. */
public interface StaffShifts {

    boolean open(String tenant, String staffId);
}
