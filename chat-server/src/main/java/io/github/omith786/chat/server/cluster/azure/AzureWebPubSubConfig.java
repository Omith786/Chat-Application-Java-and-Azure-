package io.github.omith786.chat.server.cluster.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.webpubsub.WebPubSubServiceClient;
import com.azure.messaging.webpubsub.WebPubSubServiceClientBuilder;
import com.azure.messaging.webpubsub.client.WebPubSubClient;
import com.azure.messaging.webpubsub.client.WebPubSubClientBuilder;
import com.azure.messaging.webpubsub.client.models.WebPubSubClientCredential;
import com.azure.messaging.webpubsub.client.models.WebPubSubProtocolType;
import com.azure.messaging.webpubsub.models.GetClientAccessTokenOptions;
import io.github.omith786.chat.server.cluster.BrokerConfig;
import io.github.omith786.chat.server.cluster.EventCodec;
import io.github.omith786.chat.server.cluster.MessageBroker;
import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.config.ServerInstance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the Azure Web PubSub broker when {@code chat.broker.type=azure-web-pubsub}. The two SDK
 * clients are separate beans so tests can replace them with mocks.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(BrokerConfig.AzureBrokerSelected.class)
public class AzureWebPubSubConfig {

    /** Lifetime of each client access token; the SDK asks for a fresh one on every reconnect. */
    static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    @Bean
    @ConditionalOnMissingBean
    WebPubSubServiceClient webPubSubServiceClient(ChatProperties properties) {
        return serviceClientBuilder(properties.broker().azure()).buildClient();
    }

    @Bean
    @ConditionalOnMissingBean
    WebPubSubClient webPubSubSubscriber(WebPubSubServiceClient service, ChatProperties properties,
                                        ServerInstance instance) {
        ChatProperties.Azure azure = properties.broker().azure();
        GetClientAccessTokenOptions tokenOptions = tokenOptions(instance, azure.group());
        return new WebPubSubClientBuilder()
                .credential(new WebPubSubClientCredential(() -> service.getClientAccessToken(tokenOptions).getUrl()))
                .protocol(azure.reliable()
                        ? WebPubSubProtocolType.JSON_RELIABLE_PROTOCOL
                        : WebPubSubProtocolType.JSON_PROTOCOL)
                .autoReconnect(true)
                .buildClient();
    }

    @Bean
    MessageBroker azureWebPubSubBroker(WebPubSubServiceClient service, WebPubSubClient subscriber,
                                       ChatProperties properties, ServerInstance instance) {
        return new AzureWebPubSubBroker(service, subscriber, new EventCodec(), instance.id(),
                properties.broker().azure().group());
    }

    /**
     * Builds the service client from a connection string (access key) or, failing that, from an
     * endpoint plus {@code DefaultAzureCredential}, which picks up a managed identity on App Service.
     */
    static WebPubSubServiceClientBuilder serviceClientBuilder(ChatProperties.Azure azure) {
        WebPubSubServiceClientBuilder builder = new WebPubSubServiceClientBuilder().hub(azure.hub());
        if (hasText(azure.connectionString())) {
            return builder.connectionString(azure.connectionString());
        }
        if (hasText(azure.endpoint())) {
            return builder.endpoint(azure.endpoint()).credential(new DefaultAzureCredentialBuilder().build());
        }
        throw new IllegalStateException("The azure-web-pubsub broker needs AZURE_WEBPUBSUB_CONNECTION_STRING "
                + "or AZURE_WEBPUBSUB_ENDPOINT");
    }

    /**
     * Token for this instance's subscriber connection. Putting the group in the token makes the
     * service add the connection to it on every (re)connect, so no join permission is needed and
     * nothing has to be restored after a reconnect.
     */
    static GetClientAccessTokenOptions tokenOptions(ServerInstance instance, String group) {
        return new GetClientAccessTokenOptions()
                .setUserId("chat-server-" + instance.id())
                .addGroup(group)
                .setExpiresAfter(TOKEN_LIFETIME);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
