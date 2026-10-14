package museon_online.astor_butler.telegram.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.config.AstorLinksProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupAdminNotifier {

    private final TelegramAdminNotifier telegramAdminNotifier;
    private final Environment environment;
    private final AstorLinksProperties links;

    @Value("${astor.startup.admin-notification-enabled:false}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void notifyAdminChat() {
        if (!enabled) {
            log.debug("Startup admin notification skipped: disabled by configuration");
            return;
        }

        telegramAdminNotifier.sendAnalytics(buildMessage());
    }

    String buildMessage() {
        String profiles = activeProfiles();
        String port = environment.getProperty("server.port", "8088");
        String appName = environment.getProperty("spring.application.name", "astor-butler");

        return """
                <b>Astor Butler / startup</b>
                Я в строю. Все работает.

                <b>Система</b>
                App: %s
                Profile: %s
                Backend: localhost:%s

                <b>Контур</b>
                Telegram: online
                FSM: ready
                Kafka admin stream: watching

                <b>Инструкции</b>
                Guest: %s
                Knowledge Base: https://auspicious-kryptops-863.notion.site/Astor-Butler-380a7c019f1980d78b68d8bc659c609b?source=copy_link
                Staff Chat: https://app.notion.com/p/381a7c019f1981b08ca4ed4146e630e4
                Admin Chat: https://app.notion.com/p/381a7c019f1981988530d1464d567af4
                System Chat: https://app.notion.com/p/382a7c019f198148b78aef491ceee4f6

                <b>Техника</b>
                Started at: %s
                """.formatted(
                html(appName),
                html(profiles),
                html(port),
                html(links.getGuestGuide()),
                html(Instant.now().toString())
        );
    }

    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            return "default";
        }
        return String.join(", ", Arrays.asList(profiles));
    }

    private String html(String value) {
        return value == null ? ""
                : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
