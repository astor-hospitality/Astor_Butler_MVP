package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin Saby HTTP client: service authorization, {@code X-SBISAccessToken} header, timeouts.
 * Only GET and authorization are retried; writes are never retried because Saby has no idempotency key.
 */
@Slf4j
class SabyApiClient {

    static final String TOKEN_HEADER = "X-SBISAccessToken";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final SabyReservationProperties properties;
    private volatile String token;

    SabyApiClient(SabyReservationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        Duration timeout = Duration.ofMillis(Math.max(1, properties.getTimeoutMs()));
        this.restTemplate = restTemplateBuilder
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
        this.properties = properties;
    }

    JsonNode get(String path, Map<String, ?> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getBaseUrl()))
                .path(path);
        query.forEach((name, value) -> {
            if (value != null) {
                builder.queryParam(name, value);
            }
        });
        URI uri = builder.encode().build().toUri();
        return authorizedCall(HttpMethod.GET, uri, null, path, true);
    }

    /** Never retried: a repeated create may book a second table. */
    JsonNode post(String path, Object body) {
        return authorizedCall(HttpMethod.POST, uri(path), toJson(body), path, false);
    }

    /** Never retried; an empty response body is returned as a missing node. */
    JsonNode put(String path) {
        return authorizedCall(HttpMethod.PUT, uri(path), null, path, false);
    }

    private URI uri(String path) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getBaseUrl()))
                .path(path)
                .encode()
                .build()
                .toUri();
    }

    private JsonNode authorizedCall(HttpMethod method, URI uri, String requestBody, String operation, boolean retryable) {
        boolean reauthenticated = false;
        int attempts = retryable ? 1 + Math.max(0, properties.getMaxRetries()) : 1;
        int attempt = 0;
        while (true) {
            attempt++;
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set(TOKEN_HEADER, token());
            if (requestBody != null) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
            try {
                String body = restTemplate.exchange(uri, method, new HttpEntity<>(requestBody, headers), String.class)
                        .getBody();
                if (method == HttpMethod.PUT && (body == null || body.isBlank())) {
                    return objectMapper.missingNode();
                }
                return parse(body, operation);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if (status == 401 && !reauthenticated) {
                    // 401 means the request was not processed, so a fresh token is safe even for writes.
                    log.info("Saby token rejected for {}, re-authenticating", operation);
                    token = null;
                    reauthenticated = true;
                    attempt--;
                    continue;
                }
                if (status == 401) {
                    throw new SabyApiException(SabyApiException.Kind.AUTH_FAILED, status,
                            "Saby rejected a fresh access token for " + operation);
                }
                if (isRetryableStatus(status) && attempt < attempts) {
                    log.warn("Saby {} returned HTTP {}, retrying ({}/{})", operation, status, attempt, attempts);
                    continue;
                }
                throw new SabyApiException(SabyApiException.Kind.HTTP_ERROR, status,
                        "Saby " + operation + " returned HTTP " + status);
            } catch (ResourceAccessException exception) {
                if (attempt < attempts) {
                    log.warn("Saby {} I/O failure, retrying ({}/{})", operation, attempt, attempts);
                    continue;
                }
                throw new SabyApiException(SabyApiException.Kind.TIMEOUT, 0,
                        "Saby " + operation + " did not respond in time");
            }
        }
    }

    private String token() {
        String current = token;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (token == null) {
                token = authenticate();
            }
            return token;
        }
    }

    private String authenticate() {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("app_client_id", properties.getAppClientId());
        credentials.put("app_secret", properties.getAppSecret());
        credentials.put("secret_key", properties.getSecretKey());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> request = new HttpEntity<>(toJson(credentials), headers);
        URI uri = URI.create(properties.getAuthUrl());

        int attempts = 1 + Math.max(0, properties.getMaxRetries());
        for (int attempt = 1; ; attempt++) {
            try {
                String body = restTemplate.exchange(uri, HttpMethod.POST, request, String.class).getBody();
                JsonNode json = parse(body, "service auth");
                String value = json.path("token").asText("");
                if (value.isBlank()) {
                    value = json.path("access_token").asText("");
                }
                if (value.isBlank()) {
                    throw new SabyApiException(SabyApiException.Kind.AUTH_FAILED, 0,
                            "Saby service auth response has no token");
                }
                return value;
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if (isRetryableStatus(status) && attempt < attempts) {
                    continue;
                }
                throw new SabyApiException(SabyApiException.Kind.AUTH_FAILED, status,
                        "Saby service auth returned HTTP " + status);
            } catch (ResourceAccessException exception) {
                if (attempt < attempts) {
                    continue;
                }
                // AUTH_FAILED, not TIMEOUT: the business request was never sent.
                throw new SabyApiException(SabyApiException.Kind.AUTH_FAILED, 0,
                        "Saby service auth did not respond in time");
            }
        }
    }

    private JsonNode parse(String body, String operation) {
        if (body == null || body.isBlank()) {
            throw new SabyApiException(SabyApiException.Kind.INVALID_RESPONSE, 0,
                    "Saby " + operation + " returned an empty body");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new SabyApiException(SabyApiException.Kind.INVALID_RESPONSE, 0,
                    "Saby " + operation + " returned invalid JSON");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Saby request", exception);
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
