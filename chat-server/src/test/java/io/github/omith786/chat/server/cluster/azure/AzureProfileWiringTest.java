package io.github.omith786.chat.server.cluster.azure;

import com.azure.messaging.webpubsub.WebPubSubServiceClient;
import com.azure.messaging.webpubsub.client.WebPubSubClient;
import io.github.omith786.chat.server.ChatServerApplication;
import io.github.omith786.chat.server.cluster.MessageBroker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * The {@code azure} Spring profile selects the Web PubSub broker. The SDK clients are mocks, so
 * nothing contacts Azure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The azure profile points H2 at App Service's /home; keep the test in memory.
        properties = "spring.datasource.url=jdbc:h2:mem:azure-wiring;DB_CLOSE_DELAY=-1")
@ActiveProfiles("azure")
class AzureProfileWiringTest {

    // Not reset between tests: start() is called once, when the shared context starts.
    @MockitoBean(reset = MockReset.NONE)
    WebPubSubServiceClient service;

    @MockitoBean(reset = MockReset.NONE)
    WebPubSubClient subscriber;

    @Autowired
    MessageBroker broker;

    @LocalServerPort
    int port;

    @Test
    void azureProfileSelectsAndStartsTheWebPubSubBroker() throws Exception {
        assertThat(broker).isInstanceOf(AzureWebPubSubBroker.class);
        verify(subscriber).start();

        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        // Not connected (the mock never fires a connected event), so the broker reports DOWN.
        assertThat(health.statusCode()).isEqualTo(503);
        assertThat(health.body()).contains("\"type\":\"azure-web-pubsub\"", "\"group\":\"backplane\"");
    }

    @Test
    void scalingOutWithoutASharedSessionSecretIsRefusedAtStartup() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(ChatServerApplication.class)
                .run("--server.port=0", "--chat.broker.type=azure-web-pubsub", "--chat.session.secret=",
                        "--chat.broker.azure.connection-string=Endpoint=http://127.0.0.1:1;AccessKey=a2V5;Version=1.0;")
                .close())
                .hasStackTraceContaining("CHAT_SESSION_SECRET must be set");
    }

    @Test
    void shortSecretsAreRefused() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(ChatServerApplication.class)
                .run("--server.port=0", "--chat.session.secret=too-short")
                .close())
                .hasStackTraceContaining("at least 32 characters");
    }
}
