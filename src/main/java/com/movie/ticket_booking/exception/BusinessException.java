package com.movie.ticket_booking.exception;

import org.springframework.http.HttpStatus;

/** Stable machine-readable {@code code} (BASELINE section 7); the app maps it to Vietnamese text. */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
