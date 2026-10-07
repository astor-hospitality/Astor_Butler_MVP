package museon_online.astor_butler.domain.shift;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** When the briefing runs, for which venue, and which switches are still off. */
@Component
public class ShiftBriefingProperties {
    private boolean enabled;
    private String venueCode = "AERIS";
    private String timezone = "Asia/Yekaterinburg";
    private boolean sabyEnabled;
    private boolean sabyWriteEnabled;
    private boolean paymentEnabled;
    private boolean billingEnabled;

    public ShiftBriefingProperties(
            @Value("${astor.shift.briefing.enabled:false}") boolean enabled,
            @Value("${astor.shift.briefing.venue-code:AERIS}") String venueCode,
            @Value("${astor.shift.briefing.timezone:Asia/Yekaterinburg}") String timezone,
            @Value("${astor.integrations.saby.enabled:false}") boolean sabyEnabled,
            @Value("${astor.integrations.saby.write-enabled:false}") boolean sabyWriteEnabled,
            @Value("${astor.integrations.saby.payment-enabled:false}") boolean paymentEnabled,
            @Value("${astor.billing.enabled:false}") boolean billingEnabled) {
        this.enabled = enabled;
        this.venueCode = venueCode;
        this.timezone = timezone;
        this.sabyEnabled = sabyEnabled;
        this.sabyWriteEnabled = sabyWriteEnabled;
        this.paymentEnabled = paymentEnabled;
        this.billingEnabled = billingEnabled;
    }

    public boolean isEnabled() { return enabled; }
    public String getVenueCode() { return venueCode; }

    public ZoneId zone() {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Yekaterinburg");
        }
    }

    /** Settings that change what the shift can expect from Butler today, said plainly rather than implied. */
    public List<String> warnings() {
        var warnings = new ArrayList<String>();
        if (!sabyEnabled) warnings.add("Presto не подключён: занятость столов и брони только внутри Butler");
        else if (!sabyWriteEnabled) warnings.add("Presto только читается: брони в него не уходят");
        if (!billingEnabled) warnings.add("счета выключены: предварительной суммы гостю не будет");
        if (!paymentEnabled) warnings.add("оплата выключена: кнопки «Оплатить» у гостя нет");
        return List.copyOf(warnings);
    }
}
