package com.movie.ticket_booking.architecture.fake.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately unprotected: lives in a ..controller.. package so the guard test can prove the rule bites. */
@RestController
public class UnprotectedEndpoint {
    @GetMapping("/oops")
    public String oops() {
        return "no PreAuthorize";
    }
}
