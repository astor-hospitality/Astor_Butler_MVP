package museon_online.astor_butler.integration.saby;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Journals every HTTP call that goes to Saby and lets every other call through untouched.
 * It only watches: the outcome of a call is never changed, and a journal that cannot be written does not fail the call.
 */
@Slf4j
@RequiredArgsConstructor
class SabyCallLogInterceptor implements ClientHttpRequestInterceptor {

    private static final Pattern ID_SEGMENT = Pattern.compile(
            "\\d+|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32}");
    private static final int MAX_OPERATION = 200;

    private final SabyReservationProperties properties;
    private final SabyCallLogRepository callLog;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (!isSaby(request.getURI())) {
            return execution.execute(request, body);
        }
        long startedAt = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException e) {
            record(request, 0, SabyCallLogRepository.OUTCOME_IO_ERROR, startedAt);
            throw e;
        }
        int status = status(response);
        record(request, status, status >= 200 && status < 400 ? SabyCallLogRepository.OUTCOME_OK : SabyCallLogRepository.OUTCOME_HTTP_ERROR, startedAt);
        return response;
    }

    /** The path with ids replaced and the query dropped, so that calls group by what they do and name no order or guest. */
    static String operation(URI uri) {
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
        StringBuilder operation = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                operation.append('/').append(ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment);
            }
        }
        if (operation.isEmpty()) {
            return "/";
        }
        return operation.length() <= MAX_OPERATION ? operation.toString() : operation.substring(0, MAX_OPERATION);
    }

    private boolean isSaby(URI uri) {
        String host = uri == null ? null : uri.getHost();
        return host != null && (host.equalsIgnoreCase(host(properties.getBaseUrl())) || host.equalsIgnoreCase(host(properties.getAuthUrl())));
    }

    private static String host(String url) {
        try {
            return url == null || url.isBlank() ? null : URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int status(ClientHttpResponse response) {
        try {
            return response.getStatusCode().value();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private void record(HttpRequest request, int status, String outcome, long startedAt) {
        try {
            callLog.record(request.getMethod().name(), operation(request.getURI()), status, outcome, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Saby call was not journalled: {}", e.toString());
        }
    }
}
