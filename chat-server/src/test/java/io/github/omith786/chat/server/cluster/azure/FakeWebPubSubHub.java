package io.github.omith786.chat.server.cluster.azure;

import com.azure.core.util.BinaryData;
import com.azure.messaging.webpubsub.WebPubSubServiceClient;
import com.azure.messaging.webpubsub.client.WebPubSubClient;
import com.azure.messaging.webpubsub.client.models.ConnectedEvent;
import com.azure.messaging.webpubsub.client.models.GroupMessageEvent;
import com.azure.messaging.webpubsub.client.models.WebPubSubDataFormat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An in-memory stand-in for one Web PubSub hub, built from Mockito mocks of the two SDK clients.
 * {@code sendToGroup} on any service client is delivered to every started subscriber, including
 * the sender (the real service would honour {@code excluded}; here the broker's origin check has
 * to drop the echo). Instances can be partitioned to simulate a crash or network split.
 */
final class FakeWebPubSubHub {

    private final Map<String, Consumer<GroupMessageEvent>> subscribers = new ConcurrentHashMap<>();
    private final Set<String> partitioned = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();

    /** A service client whose sends come from {@code instance}. */
    WebPubSubServiceClient serviceClient(String instance) {
        WebPubSubServiceClient service = mock(WebPubSubServiceClient.class);
        when(service.sendToGroupWithResponse(any(), any(BinaryData.class), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    if (!partitioned.contains(instance)) {
                        broadcast(invocation.getArgument(0), invocation.getArgument(1));
                    }
                    return null;
                });
        return service;
    }

    /** A subscriber client for {@code instance} that joins the hub when started. */
    WebPubSubClient subscriber(String instance) {
        WebPubSubClient client = mock(WebPubSubClient.class);
        AtomicReference<Consumer<GroupMessageEvent>> onMessage = new AtomicReference<>();
        AtomicReference<Consumer<ConnectedEvent>> onConnected = new AtomicReference<>();
        doAnswer(i -> {
            onMessage.set(i.getArgument(0));
            return null;
        }).when(client).addOnGroupMessageEventHandler(any());
        doAnswer(i -> {
            onConnected.set(i.getArgument(0));
            return null;
        }).when(client).addOnConnectedEventHandler(any());
        doAnswer(i -> {
            subscribers.put(instance, event -> {
                if (!partitioned.contains(instance)) {
                    onMessage.get().accept(event);
                }
            });
            onConnected.get().accept(new ConnectedEvent("conn-" + instance, "chat-server-" + instance));
            return null;
        }).when(client).start();
        doAnswer(i -> subscribers.remove(instance)).when(client).stop();
        return client;
    }

    /** Cuts {@code instance} off (or reconnects it): it neither sends nor receives. */
    void partition(String instance, boolean cut) {
        if (cut) {
            partitioned.add(instance);
        } else {
            partitioned.remove(instance);
        }
    }

    long delivered() {
        return delivered.get();
    }

    private void broadcast(String group, BinaryData data) {
        long id = sequence.incrementAndGet();
        subscribers.values().forEach(subscriber -> {
            delivered.incrementAndGet();
            subscriber.accept(new GroupMessageEvent(group, data, WebPubSubDataFormat.JSON, "server", id));
        });
    }
}
