package museon_online.astor_butler.speech;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;

/** Stand-in for Cloud.ru {@code /v1/audio/transcriptions}: records every request, answers from a queue. */
public final class WhisperStubServer implements AutoCloseable {

    public record Seen(String method, String path, String authorization, String contentType, byte[] body) {
        public String bodyText() { return new String(body, StandardCharsets.ISO_8859_1); }
    }

    private record Reply(int status, String body, long delayMs) { }

    private final HttpServer server;
    private final List<Seen> requests = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Reply> replies = new ArrayDeque<>();

    private WhisperStubServer(HttpServer server) {
        this.server = server;
    }

    public static WhisperStubServer start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        WhisperStubServer stub = new WhisperStubServer(server);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            stub.requests.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            Reply reply;
            synchronized (stub.replies) {
                reply = stub.replies.isEmpty() ? new Reply(200, "{\"text\":\"\"}", 0) : stub.replies.pollFirst();
            }
            if (reply.delayMs > 0) {
                try { Thread.sleep(reply.delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return stub;
    }

    public WhisperStubServer reply(int status, String body) {
        synchronized (replies) { replies.addLast(new Reply(status, body, 0)); }
        return this;
    }

    public WhisperStubServer replyAfter(long delayMs, int status, String body) {
        synchronized (replies) { replies.addLast(new Reply(status, body, delayMs)); }
        return this;
    }

    /** Base URL in the shape of {@code CLOUDRU_BASE_URL}, trailing {@code /v1} included. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public List<Seen> requests() {
        return List.copyOf(requests);
    }

    public Seen only() {
        if (requests.size() != 1) throw new AssertionError("expected exactly one request, got " + requests.size());
        return requests.get(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
