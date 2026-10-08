package museon_online.astor_butler.speech;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Stand-in for the cloud hosts (GigaChat NGW OAuth and API, Yandex SpeechKit) on one local port: every request is recorded
 * by path, and each path answers through a replaceable function. Plain HTTP; TLS is the CA tests' business.
 */
public final class RecordingStubServer implements AutoCloseable {

    public record Call(String method, String path, String query, Map<String, String> headers, byte[] body) {
        public String header(String name) { return headers.get(name.toLowerCase(java.util.Locale.ROOT)); }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    public record Answer(int status, String contentType, byte[] body) {
        public static Answer json(int status, String json) {
            return new Answer(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Call, Answer>> routes = new ConcurrentHashMap<>();

    private RecordingStubServer(HttpServer server) {
        this.server = server;
    }

    public static RecordingStubServer start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        RecordingStubServer stub = new RecordingStubServer(http);
        http.createContext("/", exchange -> {
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
            Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), Map.copyOf(headers), exchange.getRequestBody().readAllBytes());
            stub.calls.add(call);
            Function<Call, Answer> route = stub.routes.get(call.path());
            Answer answer = route == null ? Answer.json(404, "{\"message\":\"no route\"}") : route.apply(call);
            if (answer.contentType() != null) exchange.getResponseHeaders().add("Content-Type", answer.contentType());
            exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer.body());
            }
        });
        http.start();
        return stub;
    }

    public RecordingStubServer route(String path, Function<Call, Answer> answer) {
        routes.put(path, answer);
        return this;
    }

    /** A token answer in the NGW shape: {@code access_token} and {@code expires_at} in epoch milliseconds. */
    public static Answer token(String value, long expiresInMs) {
        return Answer.json(200, "{\"access_token\":\"" + value + "\",\"expires_at\":"
                + (System.currentTimeMillis() + expiresInMs) + "}");
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public List<Call> calls(String path) {
        return calls.stream().filter(call -> call.path().equals(path)).toList();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
