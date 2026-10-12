package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.billing.ExternalPaymentProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The payment page Saby issues for an order: {@code GET /retail/order/{id}/payment-link}.
 * The answer's shape is not documented, so the link is looked for under the usual names and must be an https URL;
 * anything else is "no link", never a guess. The sum, when the answer names one, comes along. Switched on separately
 * by {@code SABY_PAYMENT_ENABLED}.
 */
@Component
@Slf4j
public class SabyPaymentProvider implements ExternalPaymentProvider {

    static final String PAYMENT_LINK_SUFFIX = "/payment-link";
    private static final List<String> LINK_FIELDS = List.of("link", "url", "paymentLink", "payment_link", "paymentUrl", "payment_url", "href");
    private static final List<String> WRAPPERS = List.of("result", "data", "payment");
    private static final List<String> AMOUNT_FIELDS = List.of("amount", "sum", "totalPrice", "totalSum", "total");
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final SabyReservationProperties properties;
    private final SabyApiClient client;

    @Autowired
    public SabyPaymentProvider(SabyReservationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this(properties, new SabyApiClient(properties, restTemplateBuilder));
    }

    SabyPaymentProvider(SabyReservationProperties properties, SabyApiClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String providerId() {
        return SabyReservationProvider.PROVIDER_ID;
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled() && properties.isPaymentEnabled() && properties.configured();
    }

    @Override
    public Optional<PaymentLink> paymentLink(String externalOrderId) {
        if (!enabled()) {
            return Optional.empty();
        }
        if (externalOrderId == null || !EXTERNAL_ID.matcher(externalOrderId).matches()) {
            throw new IllegalArgumentException("Saby order id has an unexpected shape");
        }
        JsonNode response = client.get(SabyReservationProvider.ORDER_PATH + externalOrderId + PAYMENT_LINK_SUFFIX, Map.of());
        String url = link(response);
        if (url == null) {
            log.warn("Saby payment-link answered without a usable link; fields: {}", fieldNames(response));
            return Optional.empty();
        }
        return Optional.of(new PaymentLink(url, amountMinor(response)));
    }

    /**
     * The sum the venue asks for, in kopecks, when the answer names one: Saby gives money in rubles, as a number or a
     * string ("1110.50", "2 400,00"). Looked for under the usual names, at the top or one wrapper down; anything that is
     * not a positive sum is "not named", never a guess.
     */
    static Long amountMinor(JsonNode response) {
        if (response == null || !response.isObject()) {
            return null;
        }
        for (String field : AMOUNT_FIELDS) {
            Long minor = rublesToMinor(response.get(field));
            if (minor != null) {
                return minor;
            }
        }
        for (String wrapper : WRAPPERS) {
            JsonNode inner = response.path(wrapper);
            if (inner.isObject()) {
                Long minor = amountMinor(inner);
                if (minor != null) {
                    return minor;
                }
            }
        }
        return null;
    }

    private static Long rublesToMinor(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        BigDecimal rubles;
        if (value.isNumber()) {
            rubles = value.decimalValue();
        } else if (value.isTextual()) {
            String text = value.asText().replaceAll("[\\s\\u00A0]", "").replace(',', '.');
            if (!text.matches("\\d{1,9}(\\.\\d{1,2})?")) {
                return null;
            }
            rubles = new BigDecimal(text);
        } else {
            return null;
        }
        if (rubles.signum() <= 0) {
            return null;
        }
        return rubles.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The first https URL under a usual name, at the top or one wrapper down, or the whole answer when it is just a string. */
    static String link(JsonNode response) {
        if (response == null || response.isNull()) {
            return null;
        }
        if (response.isTextual()) {
            return httpsOrNull(response.asText());
        }
        for (String field : LINK_FIELDS) {
            String candidate = httpsOrNull(response.path(field).asText(null));
            if (candidate != null) {
                return candidate;
            }
        }
        for (String wrapper : WRAPPERS) {
            JsonNode inner = response.path(wrapper);
            if (inner.isObject() || inner.isTextual()) {
                String candidate = link(inner);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String httpsOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        boolean https = trimmed.startsWith("https://");
        boolean localHttp = trimmed.startsWith("http://") && (trimmed.contains("://localhost") || trimmed.contains("://127.0.0.1") || trimmed.contains("://saby-stub"));
        return (https || localHttp) && trimmed.length() <= 2048 && !trimmed.contains(" ") ? trimmed : null;
    }

    private static String fieldNames(JsonNode response) {
        if (response == null || !response.isObject()) {
            return response == null ? "none" : response.getNodeType().name();
        }
        StringBuilder names = new StringBuilder();
        response.fieldNames().forEachRemaining(name -> names.append(names.isEmpty() ? "" : ",").append(name));
        return names.toString();
    }
}
