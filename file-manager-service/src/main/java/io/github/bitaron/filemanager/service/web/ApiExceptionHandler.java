package io.github.bitaron.filemanager.service.web;

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
 * methods by most-specific declared exception type, so a thrown {@link FolderNotFoundException} or
 * {@link FileNotFoundException} - both {@link IllegalArgumentException}s, per their own javadoc -
 * always reach {@link #handleNotFound}, never {@link #handleBadRequest}.
 *
 * <p>{@link #handleNotFound} deliberately ignores the exception's own message (which names the
 * missing/foreign id) and returns a fixed detail instead: a request for another Tenant's Folder/
 * File and a request for one that never existed must be indistinguishable (ADR 0004's
 * non-enumerability rule), and a message echoing the requested id back is a needless way to
 * accidentally violate that even though the id itself is the caller's own input, not new
 * information.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({FolderNotFoundException.class, FileNotFoundException.class})
    ProblemDetail handleNotFound(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
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
