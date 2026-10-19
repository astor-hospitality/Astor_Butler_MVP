package museon_online.astor_butler.api.calllog;

import museon_online.astor_butler.api.common.GlobalApiExceptionHandler;
import museon_online.astor_butler.config.InternalApiGuardFilter;
import museon_online.astor_butler.domain.calllog.CallLogEntry;
import museon_online.astor_butler.domain.calllog.CallLogRepository;
import museon_online.astor_butler.domain.calllog.CallLogTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The export through the real internal-API guard: no token, no log. */
class CallLogControllerTest {

    private static final String TOKEN = "test-internal-token-0123456789";

    private final CallLogRepository repository = new CallLogRepository(CallLogTestDatabase.create());
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new CallLogController(repository))
            .setControllerAdvice(new GlobalApiExceptionHandler())
            .addFilters(new InternalApiGuardFilter(TOKEN))
            .build();

    @Test
    void exportsTheRowsAsCsvWithTheInternalToken() throws Exception {
        repository.append(new CallLogEntry(Instant.parse("2026-10-15T09:00:00Z"), "telegram", "abc", null, CallLogEntry.TYPE_LLM,
                "GigaChat-3-Pro", "ok", null, 1200, 300, new BigDecimal("0.1404"), 850));

        mvc.perform(get(CallLogController.EXPORT_PATH).param("from", "2026-10-15").param("to", "2026-10-16")
                        .header(InternalApiGuardFilter.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(startsWith("ts,source,pseudo_user_id")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "2026-10-15T09:00:00Z,telegram,abc,,llm,GigaChat-3-Pro,ok,,1200,300,0.1404,850")));

        mvc.perform(get(CallLogController.SUMMARY_PATH).param("from", "2026-10-15T00:00:00Z").param("to", "2026-10-16T00:00:00Z")
                        .header(InternalApiGuardFilter.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llmCalls").value(1))
                .andExpect(jsonPath("$.tokensIn").value(1200))
                .andExpect(jsonPath("$.costRub").value(0.1404));
    }

    @Test
    void withoutTheTokenTheLogIsClosed() throws Exception {
        mvc.perform(get(CallLogController.EXPORT_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(CallLogController.SUMMARY_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void aBadPeriodIsABadRequest() throws Exception {
        mvc.perform(get(CallLogController.EXPORT_PATH).param("from", "yesterday").header(InternalApiGuardFilter.HEADER, TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get(CallLogController.EXPORT_PATH).param("from", "2026-10-16").param("to", "2026-10-15")
                        .header(InternalApiGuardFilter.HEADER, TOKEN))
                .andExpect(status().isBadRequest());
    }
}
