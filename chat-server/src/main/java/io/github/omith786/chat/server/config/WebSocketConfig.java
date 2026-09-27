package io.github.omith786.chat.server.config;

import io.github.omith786.chat.server.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the chat endpoint at {@code /ws}. Plain WebSocket (no SockJS or STOMP) keeps the
 * protocol small enough for the terminal client to speak with only {@code java.net.http}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Largest frame accepted from a client; a maximum-length message is well under this. */
    static final int MAX_TEXT_MESSAGE_BYTES = 16 * 1024;
    /** Clients ping every 25 seconds, so two minutes of silence means the peer is gone. */
    static final long IDLE_TIMEOUT_MS = 120_000;

    private final ChatWebSocketHandler handler;
    private final ChatProperties properties;

    public WebSocketConfig(ChatWebSocketHandler handler, ChatProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin browsers are always allowed; non-browser clients send no Origin header.
        registry.addHandler(handler, "/ws")
                .setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxSessionIdleTimeout(IDLE_TIMEOUT_MS);
        return container;
    }
}
