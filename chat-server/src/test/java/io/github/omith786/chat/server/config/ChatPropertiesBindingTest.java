package io.github.omith786.chat.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Binding of {@code chat.*} exactly as Spring does it from properties or environment variables. */
class ChatPropertiesBindingTest {

    @Test
    void everythingHasADefault() {
        ChatProperties properties = bind(Map.of());

        assertThat(properties.broker().type()).isEqualTo(ChatProperties.BrokerType.LOCAL);
        assertThat(properties.broker().azure().hub()).isEqualTo("chat");
        assertThat(properties.broker().azure().group()).isEqualTo("backplane");
        assertThat(properties.session().ttl()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.presence().snapshotInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.limits().connectionsPerUser()).isEqualTo(5);
        assertThat(properties.rateLimits().messages())
                .isEqualTo(new ChatProperties.Bucket(10, Duration.ofMillis(500)));
        assertThat(properties.allowedOrigins()).isEmpty();
    }

    @Test
    void aPartiallyConfiguredBucketKeepsItsOtherDefault() {
        ChatProperties properties = bind(Map.of("chat.rate-limits.messages.capacity", "50"));

        assertThat(properties.rateLimits().messages())
                .isEqualTo(new ChatProperties.Bucket(50, Duration.ofMillis(500)));
        assertThat(properties.rateLimits().rooms())
                .isEqualTo(new ChatProperties.Bucket(5, Duration.ofMinutes(12)));
    }

    @Test
    void brokerTypeAcceptsTheUsualSpellings() {
        assertThat(bind(Map.of("chat.broker.type", "azure-web-pubsub")).broker().type())
                .isEqualTo(ChatProperties.BrokerType.AZURE_WEB_PUBSUB);
        assertThat(bind(Map.of("chat.broker.type", "AZURE_WEB_PUBSUB")).broker().type())
                .isEqualTo(ChatProperties.BrokerType.AZURE_WEB_PUBSUB);
    }

    @Test
    void invalidBucketsAreRejected() {
        assertThatThrownBy(() -> bind(Map.of("chat.rate-limits.typing.capacity", "0")))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining("capacity must be at least 1");
    }

    @Test
    void allowedOriginsBindFromACommaSeparatedValue() {
        assertThat(bind(Map.of("chat.allowed-origins", "https://a.example,https://b.example")).allowedOrigins())
                .containsExactly("https://a.example", "https://b.example");
    }

    @Test
    void generatedInstanceIdsAreUnique() {
        assertThat(ServerInstance.of(null).id()).startsWith("srv-").hasSize(12)
                .isNotEqualTo(ServerInstance.of(" ").id());
        assertThat(ServerInstance.of(" srv-fixed ").id()).isEqualTo("srv-fixed");
    }

    @Test
    void environmentVariableNamesFromTheReadmeBind() {
        Map<String, Object> env = Map.of(
                "CHAT_SESSION_SECRET", "x".repeat(40),
                "CHAT_PRESENCE_SNAPSHOT_INTERVAL", "15s",
                "CHAT_RATE_LIMITS_MESSAGES_CAPACITY", "25",
                "CHAT_LIMITS_MAX_ROOMS", "7",
                "CHAT_BROKER_TYPE", "azure-web-pubsub");
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env)));

        ChatProperties properties = binder.bindOrCreate("chat", ChatProperties.class);

        assertThat(properties.session().secret()).hasSize(40);
        assertThat(properties.presence().snapshotInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.rateLimits().messages().capacity()).isEqualTo(25);
        assertThat(properties.limits().maxRooms()).isEqualTo(7);
        assertThat(properties.broker().type()).isEqualTo(ChatProperties.BrokerType.AZURE_WEB_PUBSUB);
    }

    private static ChatProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("chat", ChatProperties.class);
    }
}
