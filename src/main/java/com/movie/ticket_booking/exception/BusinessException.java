package com.movie.ticket_booking.exception;

import org.springframework.http.HttpStatus;

/** Stable machine-readable {@code code} (BASELINE section 7); the app maps it to Vietnamese text. */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final java.util.List<Object> details;

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, java.util.List.of());
    }

    public BusinessException(HttpStatus status, String code, String message, java.util.List<Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = java.util.List.copyOf(details);
    }

    public java.util.List<Object> getDetails() { return details; }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
