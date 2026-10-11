package com.novel.browse.web;

import com.novel.browse.i18n.CodedException;
import com.novel.browse.i18n.Messages;
import com.novel.browse.service.BadRequestException;
import com.novel.browse.service.NotFoundException;
import com.novel.browse.web.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Unified REST exception handling, returns a JSON error body.
 * <p>
 * The {@code message} is resolved in the language of the request ({@code Accept-Language} / {@code ?lang=},
 * English by default); {@code error} stays the HTTP reason phrase.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Messages messages;

    public ApiExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Not Found", messages.of(e), request);
    }

    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ApiError> handleNoResource(Exception e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Not Found",
                messages.get("error.http.endpointNotFound", request.getRequestURI()), request);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class,
            MethodValidationException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class,
            BadRequestException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(Exception e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request", badRequestMessage(e), request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException e, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Unauthorized", messages.of(e), request);
    }

    /** Upload exceeds the spring.servlet.multipart limit */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large",
                messages.get("error.http.payloadTooLarge", e.getMessage()), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception e, HttpServletRequest request) {
        log.error("Request failed: {} {}", request.getMethod(), request.getRequestURI(), e);
        String message = e instanceof CodedException coded ? messages.of(coded) : messages.get("error.http.internal");
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", message, request);
    }

    private String badRequestMessage(Exception e) {
        if (e instanceof CodedException coded) {
            return messages.of(coded);
        }
        if (e instanceof MissingServletRequestParameterException missing) {
            return messages.get("error.request.missingParameter", missing.getParameterName());
        }
        if (e instanceof MissingServletRequestPartException part) {
            return messages.get("error.request.missingPart", part.getRequestPartName());
        }
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            return messages.get("error.request.invalidParameter", mismatch.getName());
        }
        if (e instanceof MethodArgumentNotValidException invalid) {
            return messages.get("error.request.invalidBody", invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; ")));
        }
        if (e instanceof HttpMessageNotReadableException) {
            return messages.get("error.request.malformedBody");
        }
        return messages.get("error.request.invalidArgument", e.getMessage());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String message,
                                           HttpServletRequest request) {
        ApiError body = new ApiError(Instant.now(), status.value(), error, message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
