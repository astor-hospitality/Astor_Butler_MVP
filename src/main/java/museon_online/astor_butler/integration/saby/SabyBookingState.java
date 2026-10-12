package museon_online.astor_butler.integration.saby;

import java.util.Set;

/**
 * What a Saby booking state means for Butler, from the documented codes of {@code GET /retail/order/{id}/state}
 * (saby.ru/help/integration/api/app_presto/Presto_reserv/order, read 06.10.2026):
 * <ul>
 *   <li>{@code state}: 5 черновик, 10 онлайн-заказ, 20 бронь подтверждена, 50 принята в работу, 150 заблокирован,
 *       180 отгружен, 200 закрыт, 220 отменён;</li>
 *   <li>{@code productState}: 1000 новый, 1001 принят, 1002 выполнение, 1998 отменён, 1999 выполнен.</li>
 * </ul>
 * A cancellation on either field wins. Codes outside the documented set stay {@link #UNKNOWN}, so a surprise from
 * the live account never turns into a confirmation for the guest.
 *
 * «The guest is at the table» (#151) has no documented code: {@link #SEATED} comes only from the codes configured in
 * {@link SabySeatedStates} ({@code ASTOR_SABY_SEATED_STATES}), checked right after the cancellation and before the
 * documented sets, so the venue's own answer wins over the guess from the documentation.
 */
public enum SabyBookingState {
    /** Created, not yet accepted by the venue. */
    PENDING,
    /** The venue accepted the booking. */
    CONFIRMED,
    /** The guest is at the table: a configured code matched (#151). */
    SEATED,
    /** The visit took place or the order was closed. */
    COMPLETED,
    /** Cancelled on the Saby side. */
    CANCELLED,
    /** Codes not in the documented set, or no codes at all. */
    UNKNOWN;

    private static final Set<Integer> STATE_PENDING = Set.of(5, 10);
    private static final Set<Integer> STATE_CONFIRMED = Set.of(20, 50);
    private static final Set<Integer> STATE_COMPLETED = Set.of(180, 200);
    private static final Set<Integer> STATE_CANCELLED = Set.of(220);
    private static final Set<Integer> PRODUCT_PENDING = Set.of(1000);
    private static final Set<Integer> PRODUCT_CONFIRMED = Set.of(1001, 1002);
    private static final Set<Integer> PRODUCT_COMPLETED = Set.of(1999);
    private static final Set<Integer> PRODUCT_CANCELLED = Set.of(1998);

    public static SabyBookingState of(Integer state, Integer productState) {
        return of(state, productState, SabySeatedStates.NONE);
    }

    /** The same with the seating codes of the venue; null or empty means no code ever reads as SEATED. */
    public static SabyBookingState of(Integer state, Integer productState, SabySeatedStates seated) {
        if (in(STATE_CANCELLED, state) || in(PRODUCT_CANCELLED, productState)) {
            return CANCELLED;
        }
        if (seated != null && seated.matches(state, productState)) {
            return SEATED;
        }
        if (in(STATE_COMPLETED, state) || in(PRODUCT_COMPLETED, productState)) {
            return COMPLETED;
        }
        if (in(STATE_CONFIRMED, state) || in(PRODUCT_CONFIRMED, productState)) {
            return CONFIRMED;
        }
        if (in(STATE_PENDING, state) || in(PRODUCT_PENDING, productState)) {
            return PENDING;
        }
        return UNKNOWN;
    }

    private static boolean in(Set<Integer> codes, Integer value) {
        return value != null && codes.contains(value);
    }
}
