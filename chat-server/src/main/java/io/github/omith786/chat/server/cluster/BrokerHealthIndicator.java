package io.github.omith786.chat.server.cluster;

import io.github.omith786.chat.server.config.ServerInstance;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the broker under {@code /actuator/health} as {@code broker}. DOWN means this instance
 * cannot reach the others: its own users can still chat, but not with users elsewhere.
 */
@Component("broker")
public class BrokerHealthIndicator implements HealthIndicator {

    private final MessageBroker broker;
    private final ServerInstance instance;

    public BrokerHealthIndicator(MessageBroker broker, ServerInstance instance) {
        this.broker = broker;
        this.instance = instance;
    }

    @Override
    public Health health() {
        Health.Builder builder = broker.isConnected() ? Health.up() : Health.down();
        return builder
                .withDetail("type", broker.type())
                .withDetail("instance", instance.id())
                .withDetails(broker.details())
                .build();
    }
}
