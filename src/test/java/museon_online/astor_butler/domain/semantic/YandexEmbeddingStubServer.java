package museon_online.astor_butler.domain.semantic;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Stand-in for Yandex AI Studio {@code /foundationModels/v1/textEmbedding}: records requests, answers from a queue. */
final class YandexEmbeddingStubServer implements AutoCloseable {

    record Seen(String method, String path, String authorization, String contentType, String body, long atNanos) {
    }

    private record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final List<Seen> requests = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Reply> replies = new ArrayDeque<>();

    private YandexEmbeddingStubServer(HttpServer server) {
        this.server = server;
    }

    static YandexEmbeddingStubServer start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        YandexEmbeddingStubServer stub = new YandexEmbeddingStubServer(server);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            stub.requests.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body, System.nanoTime()));
            Reply reply;
            synchronized (stub.replies) {
                reply = stub.replies.isEmpty() ? new Reply(200, vectorJson(256)) : stub.replies.pollFirst();
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

    /** The live response shape: {@code {"embedding":[...],"numTokens":"3","modelVersion":"..."}}. */
    static String vectorJson(int dimension) {
        String values = IntStream.range(0, dimension)
                .mapToObj(i -> Double.toString((i + 1) / 1000.0))
                .collect(Collectors.joining(","));
        return "{\"embedding\":[" + values + "],\"numTokens\":\"3\",\"modelVersion\":\"stub\"}";
    }

    YandexEmbeddingStubServer reply(int status, String body) {
        synchronized (replies) {
            replies.addLast(new Reply(status, body));
        }
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Seen> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
