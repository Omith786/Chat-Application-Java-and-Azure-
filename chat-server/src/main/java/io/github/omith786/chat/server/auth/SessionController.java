package io.github.omith786.chat.server.auth;

import io.github.omith786.chat.protocol.ChatRules;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.ratelimit.RateLimits;
import io.github.omith786.chat.server.web.ChatException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Username-based sessions: pick a name that nobody online is using and receive a signed token.
 * There are no passwords; see {@link SessionTokenService} for what the token does and does not prove.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionTokenService tokens;
    private final PresenceRegistry presence;
    private final RateLimits rateLimits;

    public SessionController(SessionTokenService tokens, PresenceRegistry presence, RateLimits rateLimits) {
        this.tokens = tokens;
        this.presence = presence;
        this.rateLimits = rateLimits;
    }

    /** Starts a session for {@code username}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionTokenService.IssuedSession create(@RequestBody CreateSessionRequest request,
                                                    HttpServletRequest http) {
        rateLimits.checkSession(http.getRemoteAddr());
        String username;
        try {
            username = ChatRules.normaliseUsername(request.username());
        } catch (IllegalArgumentException e) {
            throw ChatException.invalid(e);
        }
        if (presence.isOnline(username)) {
            throw new ChatException(ErrorCode.USERNAME_TAKEN, "'" + username + "' is already online");
        }
        return tokens.issue(username);
    }

    /** Returns who the bearer token belongs to. */
    @GetMapping("/me")
    public Me me(@CurrentUser String user) {
        return new Me(user, presence.isOnline(user));
    }

    /** Body of {@code POST /api/sessions}. */
    public record CreateSessionRequest(String username) {
    }

    /**
     * Response of {@code GET /api/sessions/me}.
     *
     * @param online whether the user currently has a WebSocket connection
     */
    public record Me(String username, boolean online) {
    }
}
