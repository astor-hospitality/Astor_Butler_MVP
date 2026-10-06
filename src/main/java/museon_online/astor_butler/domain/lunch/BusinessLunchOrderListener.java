package museon_online.astor_butler.domain.lunch;

/**
 * Told about a business lunch once the table is held and the venue's system has answered or was not asked.
 * A listener is a bystander: whatever it throws, the guest's order stands.
 */
public interface BusinessLunchOrderListener {

    void placed(BusinessLunchService.Request request, BusinessLunchOrder order, ExternalLunchOrderProvider.Result external);
}
