package museon_online.astor_butler.api.calllog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.domain.calllog.CallLogRepository;
import museon_online.astor_butler.domain.calllog.CallLogSummary;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Export of the unified call log (docs/operations/CALL_LOG.md). Under {@code /api/internal/**}, so
 * {@code InternalApiGuardFilter} serves it only with {@code X-Astor-Internal-Token}.
 * {@code from} is inclusive, {@code to} exclusive; each is an ISO instant ({@code 2026-10-15T00:00:00Z}) or a date
 * ({@code 2026-10-15}, midnight UTC).
 */
@RestController
@Tag(name = "Call log", description = "Unified LLM/tool/guest-message/target-action log, internal only")
public class CallLogController {

    static final String EXPORT_PATH = "/api/internal/call-log/export";
    static final String SUMMARY_PATH = "/api/internal/call-log/summary";
    private static final Duration DEFAULT_PERIOD = Duration.ofDays(30);
    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final CallLogRepository repository;

    public CallLogController(CallLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping(path = EXPORT_PATH)
    @Operation(summary = "Call log rows of a period as CSV")
    public ResponseEntity<String> export(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        Instant[] period = period(from, to);
        String filename = "call_log_" + period[0].toString().replace(':', '-') + "_" + period[1].toString().replace(':', '-') + ".csv";
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(repository.exportCsv(period[0], period[1]));
    }

    @GetMapping(path = SUMMARY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Call log totals of a period")
    public ResponseEntity<CallLogSummary> summary(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        Instant[] period = period(from, to);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(repository.summary(period[0], period[1]));
    }

    private static Instant[] period(String from, String to) {
        Instant end = to == null || to.isBlank() ? Instant.now() : parse("to", to);
        Instant start = from == null || from.isBlank() ? end.minus(DEFAULT_PERIOD) : parse("from", from);
        if (!start.isBefore(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "from must be before to");
        }
        return new Instant[]{start, end};
    }

    private static Instant parse(String name, String value) {
        String text = value.trim();
        try {
            return text.length() == 10 ? LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, name + " must be an ISO instant or date");
        }
    }
}
