package io.github.omith786.chat.server.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues and verifies stateless, HMAC-signed session tokens.
 *
 * <p>A token looks like {@code v1.<base64url(username)>.<expiry epoch seconds>.<base64url(HMAC-SHA256)>}.
 * Because verification needs only the shared secret, any server instance can accept a token
 * issued by any other, with no shared session store.
 *
 * <p>This proves that the server issued the token for that name. It is not a password check:
 * anyone may claim any username that is not currently online.
 */
public final class SessionTokenService {

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;

    /**
     * @param secret signing key, shared by every instance
     * @param ttl    lifetime of issued tokens
     * @param clock  time source (injectable for tests)
     */
    public SessionTokenService(byte[] secret, Duration ttl, Clock clock) {
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Issues a token for an already-validated, canonical username. */
    public IssuedSession issue(String username) {
        Instant expiresAt = clock.instant().plus(ttl).truncatedTo(ChronoUnit.SECONDS);
        String body = VERSION + "." + ENCODER.encodeToString(username.getBytes(StandardCharsets.UTF_8))
                + "." + expiresAt.getEpochSecond();
        return new IssuedSession(username, body + "." + sign(body), expiresAt);
    }

    /**
     * Checks a token's signature and expiry.
     *
     * @return the username, or empty if the token is malformed, forged or expired
     */
    public Optional<String> verify(String token) {
        if (token == null || token.length() > 512) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        String body = parts[0] + "." + parts[1] + "." + parts[2];
        byte[] expected = sign(body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = parts[3].getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        try {
            long expiry = Long.parseLong(parts[2]);
            if (clock.instant().getEpochSecond() >= expiry) {
                return Optional.empty();
            }
            return Optional.of(new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return ENCODER.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    /**
     * A freshly issued session.
     *
     * @param username  canonical username
     * @param token     bearer token for REST calls and the WebSocket auth frame
     * @param expiresAt when the token stops being accepted
     */
    public record IssuedSession(String username, String token, Instant expiresAt) {
    }
}
