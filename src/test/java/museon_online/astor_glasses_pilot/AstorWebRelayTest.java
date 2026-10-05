package museon_online.astor_glasses_pilot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AstorWebRelayTest {
    private MockHttpServletRequest request(String json) {
        var r = new MockHttpServletRequest(); r.setContentType("application/json");
        r.setContent(json.getBytes(StandardCharsets.UTF_8)); return r;
    }
    @Test void rejectsPrivilegedChannelsIdentitiesAndPayloadBeforeForwarding() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{calls.incrementAndGet();x.sendResponseHeaders(500,-1);x.close();});server.start();
        try {
            var relay = new AstorWebRelay("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            for (var json : new String[]{"{\"channel\":\"INTERNAL\",\"text\":\"hello\",\"payload\":{\"sessionId\":\"web-a-b\"}}",
                    "{\"channel\":\"WEB\",\"chatId\":1,\"text\":\"hello\",\"payload\":{\"sessionId\":\"web-a-b\"}}",
                    "{\"channel\":\"WEB\",\"text\":\"hello\",\"payload\":{\"sessionId\":\"web-a-b\",\"tenant\":\"foreign\"}}",
                    "{\"channel\":\"WEB\",\"text\":\"hello\",\"payload\":{\"sessionId\":\"staff:1\"}}"}) {
                assertEquals(400,relay.process(request(json)).getStatusCode().value());
            }
            assertEquals(0,calls.get());
        } finally {server.stop(0);}
    }
    @Test void forwardsOnlyRebuiltWebFieldsAndReturnsOnlyText() throws Exception {
        var body = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{body.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] reply="{\"text\":\"команде C3AG\",\"chatId\":123,\"metadata\":{\"private\":true}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,reply.length);x.getResponseBody().write(reply);x.close();});server.start();
        try {
            var relay = new AstorWebRelay("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            var response=relay.process(request("{\"channel\":\"WEB\",\"text\":\"hello\",\"payload\":{\"sessionId\":\"web-a-b\",\"site\":\"foreign\"}}"));
            assertEquals(200,response.getStatusCode().value());assertEquals(java.util.Map.of("text","команде Astor"),response.getBody());
            assertTrue(body.get().contains("astor-butler-commercial"));assertFalse(body.get().contains("foreign"));
        } finally {server.stop(0);}
    }
    @Test void leadAcknowledgementSpeaksAboutAstorNotC3agProduction() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{
            byte[] reply=("{\"text\":\"Я приняла запрос и передам его команде C3AG: посмотрим задачу по продакшену, видео и сайту, "
                    +"а менеджер вернется с человеческим ответом.\",\"nextState\":\"WEB_LEAD_RECEIVED\"}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,reply.length);x.getResponseBody().write(reply);x.close();});server.start();
        try {
            var relay = new AstorWebRelay("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            var response=relay.process(request("{\"channel\":\"WEB\",\"text\":\"Хотим внедрить Astor в ресторане\",\"payload\":{\"sessionId\":\"web-a-b\"}}"));
            assertEquals(200,response.getStatusCode().value());
            String text=String.valueOf(((java.util.Map<?,?>)response.getBody()).get("text"));
            assertTrue(text.contains("команде Astor"));
            assertFalse(text.contains("C3AG"));assertFalse(text.contains("продакшен"));assertFalse(text.contains("видео"));
        } finally {server.stop(0);}
    }
    @Test void invalidJsonAndOversizedBodyAreBounded() {
        var relay=new AstorWebRelay("http://127.0.0.1:1/");
        assertEquals(400,relay.process(request("{" )).getStatusCode().value());
        assertEquals(413,relay.process(request("x".repeat(16385))).getStatusCode().value());
    }
}
