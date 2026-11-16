package com.movie.ticket_booking.exception;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SeatConflictResponseTest {
    @Test
    void conflictReturns409WithStableCodeAndSeatDetails() throws Exception {
        MockMvcBuilders.standaloneSetup(new ConflictController())
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details[0].seatId").value(7))
                .andExpect(jsonPath("$.details[1].seatId").value(8));
    }

    @RestController
    static class ConflictController {
        @GetMapping("/test/conflict")
        void conflict() {
            throw new SeatConflictException(List.of(7L, 8L));
        }
    }
}
