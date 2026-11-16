package com.movie.ticket_booking.exception;

import com.movie.ticket_booking.dto.booking.ApiErrorResponse;
import com.movie.ticket_booking.dto.booking.SeatConflictResponse;
import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(SeatConflictException.class)
    public ResponseEntity<SeatConflictResponse> handleSeatConflict(SeatConflictException ex) {
        var details = ex.getConflictSeatIds().stream().map(SeatConflictResponse.SeatDetail::new).toList();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new SeatConflictResponse("SEAT_CONFLICT", ex.getMessage(), details));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiErrorResponse(ex.getCode(), ex.getMessage(), java.util.List.of()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAll(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ex.getClass().getSimpleName() + ": " + ex.getMessage()
        );
        return pd;
    }
}
