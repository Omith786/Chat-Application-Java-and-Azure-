package io.github.omith786.chat.server.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokenServiceTest {

    private static final byte[] SECRET = "a-test-secret-of-at-least-32-chars!!".getBytes(StandardCharsets.UTF_8);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final SessionTokenService service = new SessionTokenService(SECRET, Duration.ofHours(1), clock);

    @Test
    void issuedTokensVerifyToTheirUsername() {
        SessionTokenService.IssuedSession session = service.issue("alice");

        assertThat(session.username()).isEqualTo("alice");
        assertThat(session.expiresAt()).isEqualTo(Instant.parse("2026-01-01T11:00:00Z"));
        assertThat(service.verify(session.token())).contains("alice");
    }

    @Test
    void tokensExpire() {
        String token = service.issue("alice").token();

        now.set(Instant.parse("2026-01-01T10:59:59Z"));
        assertThat(service.verify(token)).isPresent();
        now.set(Instant.parse("2026-01-01T11:00:00Z"));
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void tamperedTokensAreRejected() {
        String token = service.issue("alice").token();
        String[] parts = token.split("\\.");

        String otherUser = String.join(".", parts[0],
                Base64.getUrlEncoder().withoutPadding().encodeToString("bob".getBytes(StandardCharsets.UTF_8)),
                parts[2], parts[3]);
        String longerExpiry = String.join(".", parts[0], parts[1], String.valueOf(Long.parseLong(parts[2]) + 3600), parts[3]);

        assertThat(service.verify(otherUser)).isEmpty();
        assertThat(service.verify(longerExpiry)).isEmpty();
    }

    @Test
    void tokensFromAnotherSecretAreRejected() {
        SessionTokenService other = new SessionTokenService(
                "a-different-secret-of-32-characters".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1), clock);
        assertThat(service.verify(other.issue("alice").token())).isEmpty();
    }

    @Test
    void instancesSharingASecretAcceptEachOthersTokens() {
        SessionTokenService otherInstance = new SessionTokenService(SECRET, Duration.ofHours(1), clock);
        assertThat(otherInstance.verify(service.issue("alice").token())).contains("alice");
    }

    @Test
    void malformedTokensAreRejected() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("v1.a.b")).isEmpty();
        assertThat(service.verify("v2.YWxpY2U.9999999999.sig")).isEmpty();
        assertThat(service.verify("v1.!!!.notanumber.sig")).isEmpty();
        assertThat(service.verify("x".repeat(1000))).isEmpty();
    }
}
