package museon_online.astor_butler.domain.calllog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rubles per 1M tokens by model-name prefix; the longest matching prefix wins, an unknown model has no cost.
 * Defaults are the GigaChat API list prices; {@code astor.call-log.tariffs} overrides or adds prefixes.
 */
@Component
@Slf4j
public class ModelTariff {

    /** Input and output price, ₽ per 1M tokens. */
    public record Rate(BigDecimal inPerMillion, BigDecimal outPerMillion) {
    }

    static final Map<String, Rate> DEFAULTS = Map.of(
            "GigaChat-2-Max", rate("569", "569"),
            "GigaChat-3-Pro", rate("73", "176"),
            "GigaChat-2-Pro", rate("500", "500"),
            "GigaChat", rate("65", "65")
    );

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, Rate> rates;

    public ModelTariff() {
        this(Map.of());
    }

    @Autowired
    public ModelTariff(CallLogProperties properties) {
        this(properties == null || properties.getTariffs() == null ? Map.of() : properties.getTariffs());
    }

    ModelTariff(Map<String, String> overrides) {
        Map<String, Rate> merged = new LinkedHashMap<>();
        DEFAULTS.forEach((prefix, rate) -> merged.put(prefix.toLowerCase(Locale.ROOT), rate));
        overrides.forEach((prefix, value) -> {
            Rate rate = parse(value);
            if (prefix == null || prefix.isBlank() || rate == null) {
                log.warn("Call log tariff override for '{}' is ignored: expected \"in,out\" rubles per 1M tokens", prefix);
            } else {
                merged.put(prefix.trim().toLowerCase(Locale.ROOT), rate);
            }
        });
        this.rates = Map.copyOf(merged);
    }

    /** The rate of the longest prefix of {@code model}, or null for an unknown model. */
    public Rate rateFor(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String name = model.trim().toLowerCase(Locale.ROOT);
        String best = null;
        for (String prefix : rates.keySet()) {
            if (name.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? null : rates.get(best);
    }

    /** Cost in rubles, 4 decimals; null when the model is unknown or no tokens were reported. */
    public BigDecimal cost(String model, Integer tokensIn, Integer tokensOut) {
        Rate rate = rateFor(model);
        if (rate == null || (tokensIn == null && tokensOut == null)) {
            return null;
        }
        BigDecimal in = rate.inPerMillion().multiply(BigDecimal.valueOf(tokensIn == null ? 0 : tokensIn));
        BigDecimal out = rate.outPerMillion().multiply(BigDecimal.valueOf(tokensOut == null ? 0 : tokensOut));
        return in.add(out).divide(MILLION, 4, RoundingMode.HALF_UP);
    }

    private static Rate rate(String in, String out) {
        return new Rate(new BigDecimal(in), new BigDecimal(out));
    }

    private static Rate parse(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            BigDecimal in = new BigDecimal(parts[0].trim());
            BigDecimal out = new BigDecimal(parts[1].trim());
            return in.signum() < 0 || out.signum() < 0 ? null : new Rate(in, out);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
