package io.github.omith786.chat.server.cluster.azure;

import com.azure.core.http.rest.RequestOptions;
import com.azure.core.util.BinaryData;
import com.azure.messaging.webpubsub.WebPubSubServiceClient;
import com.azure.messaging.webpubsub.client.WebPubSubClient;
import com.azure.messaging.webpubsub.client.models.ConnectedEvent;
import com.azure.messaging.webpubsub.client.models.DisconnectedEvent;
import com.azure.messaging.webpubsub.client.models.GroupMessageEvent;
import com.azure.messaging.webpubsub.client.models.WebPubSubDataFormat;
import com.azure.messaging.webpubsub.models.WebPubSubContentType;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.cluster.ChatEvent;
import io.github.omith786.chat.server.cluster.EventCodec;
import io.github.omith786.chat.server.support.InlineExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Azure broker against mocked Web PubSub SDK clients: no network, no Azure account. */
class AzureWebPubSubBrokerTest {

    private static final String SELF = "srv-self";
    private static final String GROUP = "backplane";

    private final EventCodec codec = new EventCodec();
    private final WebPubSubServiceClient service = mock(WebPubSubServiceClient.class);
    private final WebPubSubClient subscriber = mock(WebPubSubClient.class);
    private final List<ChatEvent> delivered = new CopyOnWriteArrayList<>();
    private AzureWebPubSubBroker broker;

    private Consumer<GroupMessageEvent> groupHandler;
    private Consumer<ConnectedEvent> connectedHandler;
    private Consumer<DisconnectedEvent> disconnectedHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startBroker() {
        broker = new AzureWebPubSubBroker(service, subscriber, codec, SELF, GROUP, new InlineExecutorService());
        broker.subscribe(delivered::add);
        broker.start();

        ArgumentCaptor<Consumer<GroupMessageEvent>> group = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<ConnectedEvent>> connected = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<DisconnectedEvent>> disconnected = ArgumentCaptor.forClass(Consumer.class);
        verify(subscriber).addOnGroupMessageEventHandler(group.capture());
        verify(subscriber).addOnConnectedEventHandler(connected.capture());
        verify(subscriber).addOnDisconnectedEventHandler(disconnected.capture());
        groupHandler = group.getValue();
        connectedHandler = connected.getValue();
        disconnectedHandler = disconnected.getValue();
    }

    @Test
    void startRegistersHandlersBeforeStartingTheSubscriber() {
        InOrder order = inOrder(subscriber);
        order.verify(subscriber).addOnGroupMessageEventHandler(any());
        order.verify(subscriber).start();
        assertThat(broker.isRunning()).isTrue();
        assertThat(broker.type()).isEqualTo("azure-web-pubsub");
    }

    @Test
    void publishDeliversLocallyThenSendsJsonToTheGroup() {
        ChatEvent event = roomMessage(SELF, 42);

        broker.publish(event);

        assertThat(delivered).containsExactly(event);
        ArgumentCaptor<BinaryData> body = ArgumentCaptor.forClass(BinaryData.class);
        ArgumentCaptor<Long> length = ArgumentCaptor.forClass(Long.class);
        verify(service).sendToGroupWithResponse(eq(GROUP), body.capture(), eq(WebPubSubContentType.APPLICATION_JSON),
                length.capture(), any(RequestOptions.class));
        assertThat(body.getValue().toString()).contains("\"event\":\"room_message\"", "\"origin\":\"srv-self\"");
        assertThat(length.getValue()).isEqualTo(body.getValue().toBytes().length);
        assertThat(broker.details()).containsEntry("sent", 1L);
    }

    @Test
    void localDeliveryStillHappensWhenAzureIsUnreachable() {
        when(service.sendToGroupWithResponse(any(), any(BinaryData.class), any(), anyLong(), any()))
                .thenThrow(new RuntimeException("connection refused"));
        ChatEvent event = roomMessage(SELF, 1);

        broker.publish(event);

        assertThat(delivered).containsExactly(event);
        assertThat(broker.details()).containsEntry("sendFailures", 1L).containsEntry("sent", 0L);
    }

    @Test
    void eventsFromOtherInstancesAreDeliveredLocally() {
        ChatEvent remote = new ChatEvent.PresenceSnapshot("srv-other", Map.of("bob", Set.of("general")));

        groupHandler.accept(groupMessage(GROUP, codec.encode(remote)));

        assertThat(delivered).containsExactly(remote);
        assertThat(broker.details()).containsEntry("received", 1L);
    }

    @Test
    void ownEchoesAreIgnored() {
        groupHandler.accept(groupMessage(GROUP, codec.encode(roomMessage(SELF, 5))));
        assertThat(delivered).isEmpty();
    }

    @Test
    void messagesForOtherGroupsOrThatCannotBeParsedAreIgnored() {
        groupHandler.accept(groupMessage("some-other-group", codec.encode(roomMessage("srv-other", 5))));
        groupHandler.accept(groupMessage(GROUP, "{\"event\":\"from_the_future\",\"origin\":\"x\"}"));
        groupHandler.accept(groupMessage(GROUP, "not json at all"));
        groupHandler.accept(new GroupMessageEvent(GROUP, null, WebPubSubDataFormat.JSON, "u", 1L));

        assertThat(delivered).isEmpty();
    }

    @Test
    void connectionStateDrivesHealth() {
        assertThat(broker.isConnected()).isFalse();
        connectedHandler.accept(new ConnectedEvent("conn-1", "chat-server-srv-self"));
        assertThat(broker.isConnected()).isTrue();
        assertThat(broker.details()).containsEntry("connectionId", "conn-1").containsEntry("group", GROUP);

        disconnectedHandler.accept(new DisconnectedEvent("conn-1", "network"));
        assertThat(broker.isConnected()).isFalse();
    }

    @Test
    void stopDrainsSendsThenStopsTheSubscriber() {
        broker.stop();

        verify(subscriber).stop();
        assertThat(broker.isRunning()).isFalse();
        broker.publish(roomMessage(SELF, 9)); // after stop: still delivered locally, never sent
        assertThat(delivered).hasSize(1);
        verify(service, never()).sendToGroupWithResponse(any(), any(BinaryData.class), any(), anyLong(), any());
    }

    private static ChatEvent roomMessage(String origin, long id) {
        return new ChatEvent.RoomMessagePosted(origin,
                new ServerFrame.RoomMessage(id, "general", "alice", "hi", Instant.parse("2026-01-01T00:00:00Z")));
    }

    private static GroupMessageEvent groupMessage(String group, String json) {
        return new GroupMessageEvent(group, BinaryData.fromString(json), WebPubSubDataFormat.JSON, "chat-server-x", 1L);
    }
}
