package io.github.omith786.chat.server.cluster;

import io.github.omith786.chat.server.config.ChatProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Chooses the broker implementation from {@code chat.broker.type}. */
@Configuration(proxyBeanMethods = false)
public class BrokerConfig {

    @Bean
    @Conditional(LocalBrokerSelected.class)
    MessageBroker inMemoryMessageBroker() {
        return new InMemoryMessageBroker();
    }

    /**
     * Binds the property the same way {@link ChatProperties} does, so "azure-web-pubsub",
     * "AZURE_WEB_PUBSUB" and "azureWebPubsub" all select the same broker.
     */
    static ChatProperties.BrokerType selectedType(ConditionContext context) {
        return Binder.get(context.getEnvironment())
                .bind("chat.broker.type", ChatProperties.BrokerType.class)
                .orElse(ChatProperties.BrokerType.LOCAL);
    }

    /** Matches when the local broker is selected (the default). */
    static final class LocalBrokerSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return selectedType(context) == ChatProperties.BrokerType.LOCAL;
        }
    }

    /** Matches when the Azure Web PubSub broker is selected. */
    public static final class AzureBrokerSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return selectedType(context) == ChatProperties.BrokerType.AZURE_WEB_PUBSUB;
        }
    }
}
