package museon_online.astor_butler.domain.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code astor.review.*}: the default keeps the review exactly where it was. */
@Component
@ConfigurationProperties(prefix = "astor.review")
@Getter
@Setter
public class ReviewProperties {

    /** When the guest is asked how it went; {@code ASTOR_REVIEW_MOMENT}. */
    private ReviewMoment moment = ReviewMoment.AFTER_CLOSE;

    public boolean onBill() {
        return moment == ReviewMoment.ON_BILL;
    }
}
