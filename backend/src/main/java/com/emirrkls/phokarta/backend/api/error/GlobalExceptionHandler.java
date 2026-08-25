package com.emirrkls.phokarta.backend.api.error;

import com.emirrkls.phokarta.backend.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status(), ex.code(), ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> bodyValidation(MethodArgumentNotValidException ex,
                                             HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Request validation failed", request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> parameterValidation(ConstraintViolationException ex,
                                                  HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                fields.put(v.getPropertyPath().toString(), v.getMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Request validation failed", request, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request could not be parsed", request, Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex,
                                       HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONFLICT",
                "The request conflicts with existing data", request, Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                              HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "This method is not supported", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API error path={} requestId={}", request.getRequestURI(),
                RequestIdFilter.from(request), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request, Map.of());
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                               HttpServletRequest request,
                                               Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(OffsetDateTime.now(ZoneOffset.UTC),
                status.value(), code, message, request.getRequestURI(),
                RequestIdFilter.from(request), fields));
    }
}
