package io.github.bitaron.filemanager.service.web;

import java.net.URI;

import io.github.bitaron.filemanager.core.accesstoken.AccessTokenNotFoundException;
import io.github.bitaron.filemanager.core.file.FileNotFoundException;
import io.github.bitaron.filemanager.core.folder.FolderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@code file-manager-core}'s validation exceptions onto RFC 7807 {@code
 * application/problem+json} responses (ADR 0004). Spring resolves {@code @ExceptionHandler}
 * methods by most-specific declared exception type, so a thrown {@link FolderNotFoundException},
 * {@link FileNotFoundException}, or {@link AccessTokenNotFoundException} - all
 * {@link IllegalArgumentException}s, per their own javadoc - always reach {@link #handleNotFound},
 * never {@link #handleBadRequest}.
 *
 * <p>{@link #handleNotFound} deliberately ignores the exception's own message (which names the
 * missing/foreign id) and returns a fixed detail instead: a request for another Tenant's Folder/
 * File and a request for one that never existed must be indistinguishable (ADR 0004's
 * non-enumerability rule), and a message echoing the requested id back is a needless way to
 * accidentally violate that even though the id itself is the caller's own input, not new
 * information. For the same reason, {@code instance} is pinned to a fixed, non-request-derived
 * value: left unset, Spring's own {@code ProblemDetail} return-value handling auto-populates it
 * from the request path (e.g. {@code /access/<token>}) whenever it's still {@code null} - which
 * would otherwise leak the very token/id a caller is guessing at back into an ostensibly
 * byte-identical 404 body (issue #36's "an expired token and a nonexistent token return
 * byte-identical 404 bodies" requirement).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final URI FIXED_NOT_FOUND_INSTANCE = URI.create("about:blank");

    @ExceptionHandler({FolderNotFoundException.class, FileNotFoundException.class, AccessTokenNotFoundException.class})
    ProblemDetail handleNotFound(IllegalArgumentException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
        problemDetail.setInstance(FIXED_NOT_FOUND_INSTANCE);
        return problemDetail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * Malformed/unreadable JSON (e.g. a null or syntactically invalid body) is raised by Spring's
     * message-converter layer before a controller method ever runs, so without this handler it
     * falls through to Spring Boot's default error page instead of ADR 0004's {@code
     * application/problem+json} shape - the same rule every other {@code 400} in this class
     * follows.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
    }
}
