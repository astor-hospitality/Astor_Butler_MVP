package museon_online.astor_butler.api.board;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The board's figures for a window of visit days; model, ledger-fallback and Saby figures by their own time. */
@Component
public class BoardMetrics {

    /** Tools whose success closes a negotiation: a reservation made or a lunch ordered. */
    static final Set<String> CLOSING_TOOLS = Set.of("reservation_create", "lunch_order_place");

    private final BoardQueries queries;

    public BoardMetrics(BoardQueries queries) {
        this.queries = queries;
    }

    /**
     * @param confirmMedianSeconds null when no visit in the window reached confirmation
     * @param modelP95Ms           null when no audited call in the window carries a latency
     */
    public record Metrics(long visits, double guestMessagesAvg, long visitsWith40Plus, long negotiationsClosed,
                          long negotiationsTotal, Long confirmMedianSeconds, double lunchConversion, long reviews,
                          long returned, long modelCalls, long modelErrors, Long modelP95Ms, long fallbacks, long sabyErrors) {
    }

    public Metrics metrics(Instant from, Instant to) {
        BoardQueries.VisitTotals totals = queries.visitTotals(from, to);
        Set<Long> negotiated = queries.negotiatedVisits(from, to);
        long closed = queries.visitsWithSuccessfulTool(from, to, CLOSING_TOOLS).stream().filter(negotiated::contains).count();
        BoardQueries.ModelTotals model = queries.modelTotals(from, to);
        double lunch = totals.visits() == 0 ? 0 : (double) queries.lunchVisits(from, to) / totals.visits();
        return new Metrics(
                totals.visits(),
                Math.round(totals.guestMessagesAvg() * 10) / 10.0,
                totals.visitsWith40Plus(),
                closed,
                negotiated.size(),
                median(queries.confirmSeconds(from, to)),
                lunch,
                queries.reviewedVisits(from, to),
                queries.returnedVisits(from, to),
                model.calls(),
                model.errors(),
                queries.modelP95Ms(from, to),
                queries.fallbackTurns(from, to),
                queries.sabyErrors(from, to));
    }

    static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
