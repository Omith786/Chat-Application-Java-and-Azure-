package io.github.omith786.chat.server.config;

import io.github.omith786.chat.server.auth.SessionTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;

/** Beans that do not belong to any one feature. */
@Configuration(proxyBeanMethods = false)
public class CoreConfig {

    private static final Logger log = LoggerFactory.getLogger(CoreConfig.class);

    /** Minimum secret length, so a typo such as "changeme" is caught at startup. */
    static final int MIN_SECRET_LENGTH = 32;

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ServerInstance serverInstance(ChatProperties properties) {
        ServerInstance instance = ServerInstance.of(properties.instanceId());
        log.info("Chat server instance id: {}", instance.id());
        return instance;
    }

    @Bean
    SessionTokenService sessionTokenService(ChatProperties properties, Clock clock) {
        return new SessionTokenService(sessionSecret(properties), properties.session().ttl(), clock);
    }

    /** One small scheduler for auth timeouts, presence heartbeats and limiter clean-up. */
    @Bean(destroyMethod = "shutdown")
    TaskScheduler chatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("chat-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    static byte[] sessionSecret(ChatProperties properties) {
        String configured = properties.session().secret();
        if (configured == null || configured.isBlank()) {
            if (properties.broker().type() != ChatProperties.BrokerType.LOCAL) {
                throw new IllegalStateException("CHAT_SESSION_SECRET must be set when running more than one "
                        + "instance, so that every instance accepts the same session tokens");
            }
            log.warn("CHAT_SESSION_SECRET is not set: using a random key, so sessions end when the server restarts");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return random;
        }
        if (configured.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("CHAT_SESSION_SECRET must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        return configured.getBytes(StandardCharsets.UTF_8);
    }
}
