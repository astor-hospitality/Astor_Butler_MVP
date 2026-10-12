package museon_online.astor_butler.domain.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code astor.billing.guest-bill-request.*} (#153 A2): the guest asks for the bill in the bot. Off by default:
 * with it off the word «счёт» is answered by the usual flow and nothing here is ever called.
 */
@Component
@ConfigurationProperties(prefix = "astor.billing.guest-bill-request")
@Getter
@Setter
public class GuestBillRequestProperties {

    /** {@code ASTOR_GUEST_BILL_REQUEST_ENABLED}: a guest's «счёт» sends the team a card and asks the review at the bill. */
    private boolean enabled = false;

    /** A second «счёт» inside this window is answered with «уже передал» and sends the team nothing. */
    private long repeatWindowMinutes = 10;

    /** The venue's clock, for «a visit today». */
    private String timezone = "Asia/Yekaterinburg";
}
