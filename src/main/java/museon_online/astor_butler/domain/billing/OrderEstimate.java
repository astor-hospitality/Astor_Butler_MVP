package museon_online.astor_butler.domain.billing;

import java.util.List;

/**
 * What an order costs by the menu Butler has, without asking the venue. It is always preliminary:
 * discounts, bonuses and a service charge are the venue's to add, and the published prices may be behind.
 *
 * @param totalMinor kopecks; null when there is nothing to price or a position has no published price
 */
public record OrderEstimate(List<Line> lines, Long totalMinor) {

    /**
     * @param unitPriceMinor kopecks for one; null when the price is not published
     * @param included the position is part of another line's price (a dish of a set) and adds nothing to the total
     */
    public record Line(String code, String title, int quantity, Long unitPriceMinor, boolean included) {

        public static Line priced(String code, String title, int quantity, Integer priceRub) {
            return new Line(code, title, quantity, priceRub == null ? null : priceRub * 100L, false);
        }

        public static Line included(String code, String title, int quantity) {
            return new Line(code, title, quantity, null, true);
        }
    }

    public static OrderEstimate of(List<Line> lines) {
        List<Line> safe = lines == null ? List.of() : List.copyOf(lines);
        List<Line> priced = safe.stream().filter(line -> !line.included()).toList();
        boolean complete = !priced.isEmpty() && priced.stream()
                .allMatch(line -> line.quantity() > 0 && line.unitPriceMinor() != null && line.unitPriceMinor() > 0);
        if (!complete) {
            return new OrderEstimate(safe, null);
        }
        long total = 0;
        for (Line line : priced) {
            total = Math.addExact(total, Math.multiplyExact(line.unitPriceMinor(), (long) line.quantity()));
        }
        return new OrderEstimate(safe, total);
    }

    public boolean complete() {
        return totalMinor != null;
    }
}
