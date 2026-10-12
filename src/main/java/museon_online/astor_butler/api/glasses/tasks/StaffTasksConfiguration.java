package museon_online.astor_butler.api.glasses.tasks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * Staff tasks in PostgreSQL, apart from the way people reach them.
 *
 * The task service used to exist only together with the JWT portal (`astor.staff.enabled`), which needs an
 * Astor OIDC issuer that production does not have yet. A task given by voice through the glasses reaches
 * Butler over the internal relay instead, so the persistence can be on (`astor.staff.tasks-enabled`) while
 * the portal, its JWT decoder and its routes stay off. Either switch creates the service; the portal
 * controller still needs its own.
 *
 * Proof photos are a third switch (`astor.staff.evidence.*`, docs/operations/STAFF_TASK_EVIDENCE.md). Off, or
 * on without a complete storage configuration, the service has no storage and the upload answers 503.
 */
@Configuration
public class StaffTasksConfiguration {
    @Bean
    @ConditionalOnExpression("${astor.staff.enabled:false} or ${astor.staff.tasks-enabled:false}")
    StaffPortalService staffPortalService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${astor.staff.evidence.enabled:false}") boolean evidence,
            @Value("${astor.staff.evidence.s3-endpoint:}") String endpoint,
            @Value("${astor.staff.evidence.s3-region:}") String region,
            @Value("${astor.staff.evidence.s3-bucket:}") String bucket,
            @Value("${astor.staff.evidence.s3-access-key:}") String accessKey,
            @Value("${astor.staff.evidence.s3-secret-key:}") String secretKey,
            @Value("${astor.staff.evidence.max-bytes:10485760}") String maxBytes,
            @Value("${astor.staff.evidence.content-types:image/jpeg,image/png,image/webp,image/heic,image/heif}") String contentTypes,
            @Value("${astor.staff.evidence.retention-days:30}") String retentionDays) {
        return new StaffPortalService(jdbc, manager, StaffEvidenceSettings.from(evidence, endpoint, region, bucket,
                accessKey, secretKey, maxBytes, contentTypes, retentionDays).orElse(null), Clock.systemUTC());
    }
}
