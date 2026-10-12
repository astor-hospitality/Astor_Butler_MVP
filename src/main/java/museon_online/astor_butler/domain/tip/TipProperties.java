package museon_online.astor_butler.domain.tip;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "astor.tip")
@Getter
@Setter
public class TipProperties {

    /** {@code ASTOR_TIP_MODE}: STAFF_CARD for the semi-automatic venue, SBP_LINK once the venue gives an SBP link. */
    private TipMode mode = TipMode.STAFF_CARD;
}
