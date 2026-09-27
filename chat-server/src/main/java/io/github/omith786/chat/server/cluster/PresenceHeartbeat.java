package io.github.omith786.chat.server.cluster;

import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.ws.ConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/**
 * Keeps presence consistent across instances. On start it asks the others for their snapshots;
 * then every {@code chat.presence.snapshot-interval} it broadcasts this instance's snapshot and
 * drops instances that have gone silent for longer than {@code chat.presence.expiry}. On a clean
 * shutdown it announces that it is leaving so its users disappear at once.
 *
 * <p>It runs in a later lifecycle phase than the broker, so it starts after the broker is
 * connected and stops before the broker disconnects.
 */
@Component
public class PresenceHeartbeat implements SmartLifecycle {

    /** Must be greater than the broker's phase. */
    public static final int PHASE = 100;

    private static final Logger log = LoggerFactory.getLogger(PresenceHeartbeat.class);

    private final MessageBroker broker;
    private final ConnectionRegistry connections;
    private final PresenceRegistry presence;
    private final ClusterEventHandler events;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final String self;
    private final Duration interval;
    private final Duration expiry;
    private volatile ScheduledFuture<?> task;
    private volatile boolean running;

    public PresenceHeartbeat(MessageBroker broker, ConnectionRegistry connections, PresenceRegistry presence,
                             ClusterEventHandler events, TaskScheduler chatScheduler, Clock clock,
                             ServerInstance instance, ChatProperties properties) {
        this.broker = broker;
        this.connections = connections;
        this.presence = presence;
        this.events = events;
        this.scheduler = chatScheduler;
        this.clock = clock;
        this.self = instance.id();
        this.interval = properties.presence().snapshotInterval();
        this.expiry = properties.presence().expiry();
        if (expiry.compareTo(interval.multipliedBy(2)) < 0) {
            log.warn("chat.presence.expiry ({}) is less than twice the snapshot interval ({}): "
                    + "healthy instances may be dropped between heartbeats", expiry, interval);
        }
    }

    @Override
    public void start() {
        running = true;
        broker.publish(new ChatEvent.SnapshotRequested(self));
        tick();
        task = scheduler.scheduleWithFixedDelay(this::tick, interval);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        broker.publish(new ChatEvent.InstanceStopping(self));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** One heartbeat: announce ourselves, then expire anyone who has gone quiet. */
    void tick() {
        try {
            broker.publish(new ChatEvent.PresenceSnapshot(self, connections.snapshot()));
            events.apply(presence.expireSilentInstances(clock.instant().minus(expiry)));
        } catch (RuntimeException e) {
            log.warn("Presence heartbeat failed", e);
        }
    }
}
