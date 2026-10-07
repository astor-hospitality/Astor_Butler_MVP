package museon_online.astor_glasses_pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.api.glasses.*;
import museon_online.astor_butler.model.ModelGateway;
import org.apache.catalina.startup.Tomcat;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.file.Files;

/** Minimal isolated runtime: no component scanning, Telegram, DB, Kafka or application.yaml. */
public final class GlassesPilotApplication {
    public static void main(String[] args) throws Exception {
        Tomcat server = new Tomcat();
        server.setBaseDir(Files.createTempDirectory("glasses-tomcat-").toString());
        server.setPort(Integer.parseInt(System.getenv().getOrDefault("GLASSES_HTTP_PORT", "8091")));
        server.getConnector().setProperty("address", System.getenv().getOrDefault("GLASSES_HTTP_ADDRESS", "127.0.0.1"));
        server.getConnector().setProperty("maxPostSize", "5242880");
        server.getConnector().setProperty("connectionTimeout", "10000");
        server.getConnector().setProperty("maxThreads", "16");
        var context = server.addContext("", Files.createTempDirectory("glasses-web-").toString());
        var spring = new AnnotationConfigWebApplicationContext();
        spring.register(Config.class);
        var servlet = Tomcat.addServlet(context, "glasses", new DispatcherServlet(spring));
        servlet.setLoadOnStartup(1);
        // Keep Spring mappings relative to the context, including the full /api/glasses path.
        context.addServletMappingDecoded("/", "glasses");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(); server.destroy(); } catch (Exception ignored) { }
        }));
        server.start();
        server.getServer().await();
    }

    @Configuration
    @EnableWebMvc
    @Import({GlassesController.class, GlassesMediaController.class, GlassesSessionEventsController.class, GlassesReportController.class,
            GlassesSessionJournal.class, GlassesAccess.class, GlassesAssistService.class, GlassesVoice.class, GlassesS3Storage.class, AstorWebRelay.class})
    public static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean ModelGateway gateway(Environment env) {
            return new YandexGlassesGateway("https://ai.api.cloud.yandex.net/v1/chat/completions",
                    env.getProperty("ASTOR_GLASSES_YANDEX_API_KEY", ""),
                    env.getProperty("ASTOR_GLASSES_YANDEX_FOLDER", ""),
                    env.getProperty("ASTOR_GLASSES_TEXT_MODEL", "yandexgpt-5.1"),
                    env.getProperty("ASTOR_GLASSES_VISION_MODEL", "qwen3.6-35b-a3b"));
        }
    }
}
