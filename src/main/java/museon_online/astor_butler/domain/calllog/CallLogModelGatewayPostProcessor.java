package museon_online.astor_butler.domain.calllog;

import org.springframework.beans.factory.config.BeanPostProcessor;

import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ToolCallingModel;

import java.util.function.Supplier;

/**
 * Wraps whichever {@link ModelGateway} bean the application selected ({@code astor.model.provider} picks exactly one
 * {@code @Service}) in {@link CallLogModelGateway}, so every caller is counted without touching any of them.
 * Registered only while {@code astor.call-log.enabled=true}: with the flag off there is no wrapper at all.
 * Gateways built by hand (the glasses pilot app, the glasses GigaChat delegate) are not beans and are not wrapped.
 */
public class CallLogModelGatewayPostProcessor implements BeanPostProcessor {

    private final Supplier<CallLogWriter> writer;

    public CallLogModelGatewayPostProcessor(Supplier<CallLogWriter> writer) {
        this.writer = writer;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ModelGateway gateway && !(bean instanceof CallLogModelGateway)) {
            // GigaChat also calls tools: the wrapper must stay a ToolCallingModel, or the agent loses its model.
            return bean instanceof ToolCallingModel
                    ? new CallLogToolCallingModelGateway(gateway, writer)
                    : new CallLogModelGateway(gateway, writer);
        }
        return bean;
    }
}
