package io.github.omith786.chat.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * All application settings under the {@code chat.*} prefix. Every value can be supplied as an
 * environment variable using Spring's relaxed binding, e.g. {@code CHAT_SESSION_SECRET}.
 *
 * @param instanceId     id of this server process; generated when blank
 * @param allowedOrigins extra browser origins allowed to open the WebSocket (same-origin is always allowed)
 */
@ConfigurationProperties("chat")
public record ChatProperties(
        String instanceId,
        List<String> allowedOrigins,
        @DefaultValue Session session,
        @DefaultValue Limits limits,
        @DefaultValue RateLimits rateLimits,
        @DefaultValue Presence presence,
        @DefaultValue Broker broker) {

    public ChatProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    /**
     * @param secret HMAC key for session tokens; must be shared by every instance when scaled out
     * @param ttl    how long an issued session token stays valid
     */
    public record Session(String secret, @DefaultValue("12h") Duration ttl) {
    }

    /**
     * Guard rails that keep one client from exhausting the server.
     *
     * @param connectionsPerUser simultaneous WebSocket connections allowed per username
     * @param roomsPerConnection rooms one connection may join at once
     * @param maxRooms           total rooms that may exist
     * @param authTimeout        how long a new connection has to send its auth frame
     * @param historyPageSize    default number of messages per history request
     * @param maxHistoryPageSize upper bound for the {@code limit} query parameter
     */
    public record Limits(
            @DefaultValue("5") int connectionsPerUser,
            @DefaultValue("50") int roomsPerConnection,
            @DefaultValue("200") int maxRooms,
            @DefaultValue("10s") Duration authTimeout,
            @DefaultValue("50") int historyPageSize,
            @DefaultValue("100") int maxHistoryPageSize) {
    }

    /**
     * Token-bucket settings. Each bucket allows a burst of {@code capacity} and then one action
     * per {@code refillPeriod}. Any field left unset takes that bucket's default, so setting just
     * {@code CHAT_RATE_LIMITS_MESSAGES_CAPACITY} works.
     */
    public record RateLimits(Bucket messages, Bucket typing, Bucket sessions, Bucket rooms) {

        public RateLimits {
            // Per user: bursts of 10 messages, then two per second.
            messages = Bucket.orDefault(messages, 10, Duration.ofMillis(500));
            // Per user: clients send at most one typing signal every couple of seconds.
            typing = Bucket.orDefault(typing, 5, Duration.ofSeconds(1));
            // Per client IP: ten sign-ins, then one every six seconds.
            sessions = Bucket.orDefault(sessions, 10, Duration.ofSeconds(6));
            // Per user: five new rooms, then one every twelve minutes.
            rooms = Bucket.orDefault(rooms, 5, Duration.ofMinutes(12));
        }
    }

    /**
     * One token-bucket configuration.
     *
     * @param capacity     largest burst allowed
     * @param refillPeriod time to earn back one token
     */
    public record Bucket(Integer capacity, Duration refillPeriod) {

        /** Fills unset fields of {@code configured} from the defaults, then validates the result. */
        static Bucket orDefault(Bucket configured, int defaultCapacity, Duration defaultRefill) {
            Integer capacity = configured == null || configured.capacity() == null ? defaultCapacity : configured.capacity();
            Duration refill = configured == null || configured.refillPeriod() == null ? defaultRefill : configured.refillPeriod();
            return validated(capacity, refill);
        }

        /** Creates a bucket, rejecting a capacity below 1 or a period that is not positive. */
        public static Bucket validated(int capacity, Duration refillPeriod) {
            if (capacity < 1) {
                throw new IllegalArgumentException("Bucket capacity must be at least 1");
            }
            if (refillPeriod == null || refillPeriod.isNegative() || refillPeriod.isZero()) {
                throw new IllegalArgumentException("Bucket refill period must be positive");
            }
            return new Bucket(capacity, refillPeriod);
        }
    }

    /**
     * Cross-instance presence settings. Each instance broadcasts its full list of connected users
     * every {@code snapshotInterval}; an instance that is silent for {@code expiry} is presumed dead.
     */
    public record Presence(
            @DefaultValue("60s") Duration snapshotInterval,
            @DefaultValue("180s") Duration expiry) {
    }

    /** Which {@code MessageBroker} fans messages out between server instances. */
    public record Broker(@DefaultValue("local") BrokerType type, @DefaultValue Azure azure) {
    }

    /** Available broker implementations. */
    public enum BrokerType {
        /** In-process delivery only: correct for a single instance, costs nothing. */
        LOCAL,
        /** Azure Web PubSub as a backplane between several instances. */
        AZURE_WEB_PUBSUB
    }

    /**
     * Azure Web PubSub settings. Give either a connection string (access key) or an endpoint,
     * in which case {@code DefaultAzureCredential} is used (e.g. an App Service managed identity).
     *
     * @param reliable use the reliable JSON subprotocol, which resends messages missed during a
     *                 brief reconnect
     */
    public record Azure(
            String connectionString,
            String endpoint,
            @DefaultValue("chat") String hub,
            @DefaultValue("backplane") String group,
            @DefaultValue("true") boolean reliable) {
    }
}
