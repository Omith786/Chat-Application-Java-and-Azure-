package io.github.omith786.chat.server.cluster.azure;

import com.azure.messaging.webpubsub.WebPubSubServiceClient;
import com.azure.messaging.webpubsub.client.WebPubSubClient;
import com.azure.messaging.webpubsub.client.models.ConnectedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.omith786.chat.server.cluster.ChatEvent;
import io.github.omith786.chat.server.cluster.EventCodec;
import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.support.InlineExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Exercises the <em>real</em> Web PubSub service SDK, pointed at a local HTTP server that stands
 * in for Azure. This checks the exact REST call the broker makes and the client access token it
 * mints, without an Azure account. The access key is a random test value.
 */
class AzureWebPubSubSdkTest {

    private static final String ACCESS_KEY = Base64.getEncoder()
            .encodeToString("not-a-real-key-just-32-bytes-ok!".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper json = new ObjectMapper();
    private final List<Captured> requests = new CopyOnWriteArrayList<>();
    private HttpServer fakeAzure;
    private String connectionString;

    record Captured(String method, String path, Map<String, String> query, String contentType,
                    String authorization, String body) {
    }

    @BeforeEach
    void startFakeService() throws IOException {
        fakeAzure = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAzure.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            requests.add(new Captured(exchange.getRequestMethod(), uri.getPath(), query(uri.getRawQuery()),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        fakeAzure.start();
        int port = fakeAzure.getAddress().getPort();
        connectionString = "Endpoint=http://127.0.0.1:" + port + ";AccessKey=" + ACCESS_KEY + ";Version=1.0;";
    }

    @AfterEach
    void stopFakeService() {
        fakeAzure.stop(0);
    }

    @Test
    void publishMakesTheDocumentedSendToGroupRestCall() throws Exception {
        WebPubSubServiceClient service = AzureWebPubSubConfig.serviceClientBuilder(azure(connectionString, null)).buildClient();
        WebPubSubClient subscriber = mock(WebPubSubClient.class);
        AzureWebPubSubBroker broker = new AzureWebPubSubBroker(service, subscriber, new EventCodec(), "srv-a",
                "backplane", new InlineExecutorService());
        broker.start();
        connected(subscriber).accept(new ConnectedEvent("conn-123", "chat-server-srv-a"));

        ChatEvent event = new ChatEvent.UserConnected("srv-a", "alice");
        broker.publish(event);

        assertThat(requests).hasSize(1);
        Captured request = requests.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/hubs/chat/groups/backplane/:send");
        assertThat(request.query()).containsKey("api-version").containsEntry("excluded", "conn-123");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(request.authorization()).startsWith("Bearer ");
        assertThat(json.readTree(request.body())).isEqualTo(json.readTree(new EventCodec().encode(event)));

        // The service validates this JWT against the access key; check it targets the right URL.
        JsonNode claims = jwtClaims(request.authorization().substring("Bearer ".length()));
        assertThat(claims.get("aud").asText()).contains("/api/hubs/chat/groups/backplane/:send");
        assertThat(claims.get("exp").asLong()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    void beforeTheSubscriberConnectsNothingIsExcluded() {
        WebPubSubServiceClient service = AzureWebPubSubConfig.serviceClientBuilder(azure(connectionString, null)).buildClient();
        AzureWebPubSubBroker broker = new AzureWebPubSubBroker(service, mock(WebPubSubClient.class), new EventCodec(),
                "srv-a", "backplane", new InlineExecutorService());

        broker.publish(new ChatEvent.SnapshotRequested("srv-a"));

        assertThat(requests).singleElement().satisfies(r -> assertThat(r.query()).doesNotContainKey("excluded"));
    }

    @Test
    void subscriberTokensPutTheConnectionInTheBackplaneGroup() throws Exception {
        WebPubSubServiceClient service = AzureWebPubSubConfig.serviceClientBuilder(azure(connectionString, null)).buildClient();

        String url = service.getClientAccessToken(
                AzureWebPubSubConfig.tokenOptions(new ServerInstance("srv-a"), "backplane")).getUrl();

        URI uri = URI.create(url);
        assertThat(uri.getScheme()).isEqualTo("ws");
        assertThat(uri.getPath()).isEqualTo("/client/hubs/chat");
        JsonNode claims = jwtClaims(query(uri.getRawQuery()).get("access_token"));
        assertThat(claims.get("sub").asText()).isEqualTo("chat-server-srv-a");
        // Initial group membership granted by the token itself, so no join permission is needed.
        assertThat(claims.get("webpubsub.group").get(0).asText()).isEqualTo("backplane");
        assertThat(claims.get("role")).as("no extra permissions requested").isNull();
        long secondsLeft = claims.get("exp").asLong() - System.currentTimeMillis() / 1000;
        assertThat(secondsLeft).isBetween(AzureWebPubSubConfig.TOKEN_LIFETIME.toSeconds() - 60,
                AzureWebPubSubConfig.TOKEN_LIFETIME.toSeconds());
        assertThat(requests).as("tokens are signed locally with the access key").isEmpty();
    }

    @Test
    void theSubscriberClientCanBeBuiltWithoutConnecting() {
        ChatProperties.Azure azure = azure(connectionString, null);
        WebPubSubServiceClient service = AzureWebPubSubConfig.serviceClientBuilder(azure).buildClient();
        ChatProperties properties = new ChatProperties(null, null, null, null, null, null,
                new ChatProperties.Broker(ChatProperties.BrokerType.AZURE_WEB_PUBSUB, azure));

        WebPubSubClient client = new AzureWebPubSubConfig().webPubSubSubscriber(service, properties, new ServerInstance("srv-a"));

        assertThat(client).isNotNull();
        assertThat(requests).isEmpty();
    }

    @Test
    void missingConfigurationFailsFastWithAClearMessage() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AzureWebPubSubConfig.serviceClientBuilder(azure(" ", null)))
                .withMessageContaining("AZURE_WEBPUBSUB_CONNECTION_STRING");
    }

    @Test
    void anEndpointAloneSelectsTokenCredentials() {
        // Building must not contact Azure; DefaultAzureCredential is only used on the first request.
        assertThat(AzureWebPubSubConfig.serviceClientBuilder(azure(null, "https://example.webpubsub.azure.com")).buildClient())
                .isNotNull();
    }

    private static ChatProperties.Azure azure(String connectionString, String endpoint) {
        return new ChatProperties.Azure(connectionString, endpoint, "chat", "backplane", true);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<ConnectedEvent> connected(WebPubSubClient subscriber) {
        ArgumentCaptor<Consumer<ConnectedEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(subscriber).addOnConnectedEventHandler(captor.capture());
        return captor.getValue();
    }

    private JsonNode jwtClaims(String jwt) throws IOException {
        String[] parts = jwt.split("\\.");
        return json.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }
}
