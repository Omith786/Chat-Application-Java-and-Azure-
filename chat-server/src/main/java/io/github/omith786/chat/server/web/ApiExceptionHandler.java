package io.github.omith786.chat.server.web;

import io.github.omith786.chat.protocol.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * Converts {@link ChatException} into RFC 9457 problem details with a stable {@code code}
 * property; Spring MVC's own errors (bad parameters, unreadable bodies) keep their default
 * problem responses via the superclass.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.ofEntries(
            Map.entry(ErrorCode.INVALID, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.BAD_FRAME, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.UNAUTHORISED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.NOT_IN_ROOM, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.NO_SUCH_ROOM, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.USERNAME_TAKEN, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.ROOM_EXISTS, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.USER_OFFLINE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.LIMIT_REACHED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS));

    @ExceptionHandler(ChatException.class)
    ResponseEntity<ProblemDetail> handleChatException(ChatException e) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("code", e.code());
        return ResponseEntity.status(status).body(problem);
    }
}
