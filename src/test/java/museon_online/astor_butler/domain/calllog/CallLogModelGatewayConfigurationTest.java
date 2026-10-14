package museon_online.astor_butler.domain.calllog;

import museon_online.astor_butler.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The selected gateway bean is wrapped only while the flag is on; injection by the interface keeps working. */
class CallLogModelGatewayConfigurationTest {

    private final ModelGateway selected = mock(ModelGateway.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CallLogModelGatewayConfiguration.class)
            .withBean("gigaChatModelGateway", ModelGateway.class, () -> selected)
            .withBean(CallLogWriter.class, () -> mock(CallLogWriter.class));

    @Test
    void onTheSelectedGatewayIsWrapped() {
        runner.withPropertyValues("astor.call-log.enabled=true").run(context -> {
            ModelGateway gateway = context.getBean(ModelGateway.class);
            assertThat(gateway).isInstanceOf(CallLogModelGateway.class);
            assertThat(((CallLogModelGateway) gateway).delegate()).isSameAs(selected);
        });
    }

    @Test
    void offThereIsNoWrapper() {
        runner.run(context -> assertThat(context.getBean(ModelGateway.class)).isSameAs(selected));
    }
}
