package io.github.omith786.chat.server.ratelimit;

import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.web.ChatException;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** The application's named rate limiters, configured from {@code chat.rate-limits.*}. */
@Component
public class RateLimits {

    private static final Duration EVICTION_INTERVAL = Duration.ofMinutes(5);

    private final RateLimiter messages;
    private final RateLimiter typing;
    private final RateLimiter sessions;
    private final RateLimiter rooms;
    private final TaskScheduler scheduler;

    public RateLimits(ChatProperties properties, TaskScheduler chatScheduler) {
        ChatProperties.RateLimits limits = properties.rateLimits();
        this.messages = new RateLimiter(limits.messages(), System::nanoTime);
        this.typing = new RateLimiter(limits.typing(), System::nanoTime);
        this.sessions = new RateLimiter(limits.sessions(), System::nanoTime);
        this.rooms = new RateLimiter(limits.rooms(), System::nanoTime);
        this.scheduler = chatScheduler;
    }

    @PostConstruct
    void scheduleEviction() {
        scheduler.scheduleWithFixedDelay(() -> {
            messages.evictIdle();
            typing.evictIdle();
            sessions.evictIdle();
            rooms.evictIdle();
        }, EVICTION_INTERVAL);
    }

    /** Throws {@code rate_limited} if {@code user} is sending messages too quickly. */
    public void checkMessage(String user) {
        require(messages.tryAcquire(user), "You are sending messages too quickly");
    }

    /** Typing signals are best-effort, so callers drop them silently when this returns false. */
    public boolean allowTyping(String user) {
        return typing.tryAcquire(user);
    }

    /** Throws {@code rate_limited} if {@code clientAddress} is creating sessions too quickly. */
    public void checkSession(String clientAddress) {
        require(sessions.tryAcquire(clientAddress), "Too many sign-in attempts, try again shortly");
    }

    /** Throws {@code rate_limited} if {@code user} is creating rooms too quickly. */
    public void checkRoomCreation(String user) {
        require(rooms.tryAcquire(user), "You are creating rooms too quickly");
    }

    private static void require(boolean allowed, String message) {
        if (!allowed) {
            throw new ChatException(ErrorCode.RATE_LIMITED, message);
        }
    }
}
