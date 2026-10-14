package museon_online.astor_butler.domain.calllog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rubles per 1M tokens by model name. A name is compared by its last path segment ({@code GigaChat/GigaChat-3-Pro}
 * is {@code GigaChat-3-Pro}), without case, and matches a tariff when it is that name, or that name followed by a
 * version tag ({@code :1.0.26}) or {@code -preview}. So the bare {@code GigaChat} (Lite) price never swallows a
 * variant it does not know: an unknown model has no cost and one WARN names it.
 * {@code astor.call-log.tariffs} ({@code ASTOR_CALL_LOG_TARIFFS}) overrides or adds: {@code "model=in,out;model=in,out"}.
 */
@Component
@Slf4j
public class ModelTariff {

    /** Input and output price, ₽ per 1M tokens. */
    public record Rate(BigDecimal inPerMillion, BigDecimal outPerMillion) {
    }

    static final Map<String, Rate> DEFAULTS = Map.of(
            "GigaChat-2-Max", rate("569", "569"),
            "GigaChat-Max", rate("569", "569"),
            "GigaChat-3-Pro", rate("73", "176"),
            "GigaChat-2-Pro", rate("500", "500"),
            "GigaChat-Pro", rate("500", "500"),
            "GigaChat", rate("65", "65")
    );

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int MAX_WARNED = 200;

    private final Map<String, Rate> rates;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ModelTariff() {
        this("");
    }

    @Autowired
    public ModelTariff(CallLogProperties properties) {
        this(properties == null ? "" : properties.getTariffs());
    }

    ModelTariff(String overrides) {
        Map<String, Rate> merged = new LinkedHashMap<>();
        DEFAULTS.forEach((name, rate) -> merged.put(key(name), rate));
        if (overrides != null && !overrides.isBlank()) {
            for (String entry : overrides.split(";")) {
                if (entry.isBlank()) {
                    continue;
                }
                int eq = entry.indexOf('=');
                String name = eq < 0 ? "" : key(entry.substring(0, eq));
                Rate rate = eq < 0 ? null : parse(entry.substring(eq + 1));
                if (name.isEmpty() || rate == null) {
                    log.warn("Call log tariff entry '{}' is ignored: expected model=in,out rubles per 1M tokens", entry.trim());
                } else {
                    merged.put(name, rate);
                }
            }
        }
        this.rates = Map.copyOf(merged);
    }

    /** The rate of the model, or null for an unknown one. */
    public Rate rateFor(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String name = key(model);
        Rate exact = rates.get(name);
        if (exact != null) {
            return exact;
        }
        String best = null;
        for (String known : rates.keySet()) {
            if (name.length() > known.length() && name.startsWith(known)
                    && (name.charAt(known.length()) == ':' || name.substring(known.length()).startsWith("-preview"))
                    && (best == null || known.length() > best.length())) {
                best = known;
            }
        }
        return best == null ? null : rates.get(best);
    }

    /** Cost in rubles, 4 decimals; null when the model is unknown or no tokens were reported. */
    public BigDecimal cost(String model, Integer tokensIn, Integer tokensOut) {
        if (tokensIn == null && tokensOut == null) {
            return null;
        }
        Rate rate = rateFor(model);
        if (rate == null) {
            if (model != null && !model.isBlank() && warned.size() < MAX_WARNED && warned.add(key(model))) {
                log.warn("Call log has no tariff for model '{}': its cost stays empty (ASTOR_CALL_LOG_TARIFFS)", model.trim());
            }
            return null;
        }
        BigDecimal in = rate.inPerMillion().multiply(BigDecimal.valueOf(tokensIn == null ? 0 : tokensIn));
        BigDecimal out = rate.outPerMillion().multiply(BigDecimal.valueOf(tokensOut == null ? 0 : tokensOut));
        return in.add(out).divide(MILLION, 4, RoundingMode.HALF_UP);
    }

    /** Last path segment, trimmed, lower case. */
    private static String key(String model) {
        String name = model.trim();
        int slash = name.lastIndexOf('/');
        return (slash >= 0 ? name.substring(slash + 1) : name).trim().toLowerCase(Locale.ROOT);
    }

    private static Rate rate(String in, String out) {
        return new Rate(new BigDecimal(in), new BigDecimal(out));
    }

    private static Rate parse(String value) {
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
