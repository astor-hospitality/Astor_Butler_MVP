package museon_online.astor_butler.api.glasses.tasks;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Staff tasks in PostgreSQL, apart from the way people reach them.
 *
 * The task service used to exist only together with the JWT portal (`astor.staff.enabled`), which needs an
 * Astor OIDC issuer that production does not have yet. A task given by voice through the glasses reaches
 * Butler over the internal relay instead, so the persistence can be on (`astor.staff.tasks-enabled`) while
 * the portal, its JWT decoder and its routes stay off. Either switch creates the service; the portal
 * controller still needs its own.
 */
@Configuration
public class StaffTasksConfiguration {
    @Bean
    @ConditionalOnExpression("${astor.staff.enabled:false} or ${astor.staff.tasks-enabled:false}")
    StaffPortalService staffPortalService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new StaffPortalService(jdbc, manager);
    }
}
