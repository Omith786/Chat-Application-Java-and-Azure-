package io.github.omith786.chat.server.cluster.azure;

import com.azure.core.http.rest.RequestOptions;
import com.azure.core.util.BinaryData;
import com.azure.messaging.webpubsub.WebPubSubServiceClient;
import com.azure.messaging.webpubsub.client.WebPubSubClient;
import com.azure.messaging.webpubsub.client.models.ConnectedEvent;
import com.azure.messaging.webpubsub.client.models.DisconnectedEvent;
import com.azure.messaging.webpubsub.client.models.GroupMessageEvent;
import com.azure.messaging.webpubsub.models.WebPubSubContentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.omith786.chat.server.cluster.ChatEvent;
import io.github.omith786.chat.server.cluster.EventCodec;
import io.github.omith786.chat.server.cluster.LocalSubscribers;
import io.github.omith786.chat.server.cluster.MessageBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A {@link MessageBroker} that uses an Azure Web PubSub group as a backplane between server
 * instances.
 *
 * <p>Browsers and terminal clients still connect to this server, not to Web PubSub. Each server
 * instance additionally holds one Web PubSub client connection that is a member of a single
 * group (default {@code backplane}):
 * <ul>
 *   <li><b>publish</b>: the event is delivered to local subscribers immediately, then sent to the
 *       group with the service SDK ({@code sendToGroup}), excluding this instance's own
 *       connection so the service does not echo it back;</li>
 *   <li><b>receive</b>: group messages arrive on the client SDK connection, are decoded and
 *       delivered to local subscribers. Events whose {@code origin} is this instance are ignored
 *       as a second line of defence against echoes.</li>
 * </ul>
 *
 * <p>Sends run on a single background thread, so publishing never blocks a WebSocket thread on
 * an HTTP call and each instance's events reach the others in the order they were published.
 */
public final class AzureWebPubSubBroker implements MessageBroker, SmartLifecycle {

    /** Starts before {@code PresenceHeartbeat} and stops after it. */
    public static final int PHASE = 0;

    private static final Logger log = LoggerFactory.getLogger(AzureWebPubSubBroker.class);
    private static final long SHUTDOWN_DRAIN_SECONDS = 5;

    private final WebPubSubServiceClient service;
    private final WebPubSubClient subscriber;
    private final EventCodec codec;
    private final String self;
    private final String group;
    private final ExecutorService sender;
    private final LocalSubscribers subscribers = new LocalSubscribers();

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong sendFailures = new AtomicLong();
    private volatile String connectionId;
    private volatile boolean connected;
    private volatile boolean running;

    /**
     * @param service    service SDK client, used to publish to the group
     * @param subscriber client SDK connection whose access token already places it in {@code group}
     * @param self       this instance's id
     * @param group      Web PubSub group shared by all instances
     */
    public AzureWebPubSubBroker(WebPubSubServiceClient service, WebPubSubClient subscriber,
                                EventCodec codec, String self, String group) {
        this(service, subscriber, codec, self, group, Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "webpubsub-sender");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Test seam: lets tests run sends synchronously. */
    AzureWebPubSubBroker(WebPubSubServiceClient service, WebPubSubClient subscriber, EventCodec codec,
                         String self, String group, ExecutorService sender) {
        this.service = service;
        this.subscriber = subscriber;
        this.codec = codec;
        this.self = self;
        this.group = group;
        this.sender = sender;
    }

    @Override
    public void publish(ChatEvent event) {
        subscribers.deliver(event);
        String json = codec.encode(event);
        try {
            sender.execute(() -> sendToGroup(json));
        } catch (RejectedExecutionException e) {
            log.debug("Broker stopped; not forwarding {}", event.getClass().getSimpleName());
        }
    }

    @Override
    public void subscribe(Consumer<ChatEvent> listener) {
        subscribers.add(listener);
    }

    @Override
    public String type() {
        return "azure-web-pubsub";
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("group", group);
        details.put("connectionId", connectionId == null ? "none" : connectionId);
        details.put("sent", sent.get());
        details.put("received", received.get());
        details.put("sendFailures", sendFailures.get());
        return details;
    }

    @Override
    public void start() {
        subscriber.addOnConnectedEventHandler(this::onConnected);
        subscriber.addOnDisconnectedEventHandler(this::onDisconnected);
        subscriber.addOnGroupMessageEventHandler(this::onGroupMessage);
        subscriber.start();
        running = true;
        log.info("Joined Azure Web PubSub group '{}' as instance {}", group, self);
    }

    @Override
    public void stop() {
        running = false;
        sender.shutdown();
        try {
            // Let the final events (such as "instance stopping") reach the other instances.
            if (!sender.awaitTermination(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Gave up waiting for queued Web PubSub sends");
                sender.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        subscriber.stop();
        connected = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    void onConnected(ConnectedEvent event) {
        connectionId = event.getConnectionId();
        connected = true;
        log.info("Web PubSub connection {} established", connectionId);
    }

    void onDisconnected(DisconnectedEvent event) {
        connected = false;
        log.warn("Web PubSub connection lost ({}); the client SDK will reconnect", event.getReason());
    }

    void onGroupMessage(GroupMessageEvent event) {
        if (!group.equals(event.getGroup()) || event.getData() == null) {
            return;
        }
        ChatEvent chatEvent;
        try {
            chatEvent = codec.decode(new String(event.getData().toBytes(), StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            log.warn("Ignoring malformed backplane message: {}", e.getOriginalMessage());
            return;
        }
        if (self.equals(chatEvent.origin())) {
            return;
        }
        received.incrementAndGet();
        subscribers.deliver(chatEvent);
    }

    private void sendToGroup(String json) {
        RequestOptions options = new RequestOptions();
        String ownConnection = connectionId;
        if (ownConnection != null) {
            // Saves a message from the free tier's daily quota for every event we publish.
            options.addQueryParam("excluded", ownConnection);
        }
        BinaryData body = BinaryData.fromString(json);
        try {
            service.sendToGroupWithResponse(group, body, WebPubSubContentType.APPLICATION_JSON,
                    body.getLength(), options);
            sent.incrementAndGet();
        } catch (RuntimeException e) {
            // Local users already have the event; remote ones will converge via the next snapshot
            // for presence, but a message sent now is only in history for them.
            sendFailures.incrementAndGet();
            log.warn("Failed to publish to Web PubSub group '{}': {}", group, e.toString());
        }
    }
}
