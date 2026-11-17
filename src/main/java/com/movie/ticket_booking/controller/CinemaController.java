package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.CinemaRequest;
import com.movie.ticket_booking.dto.CinemaResponse;
import com.movie.ticket_booking.service.CinemaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/cinemas", "/api/v1/cinemas"})
@RequiredArgsConstructor
public class CinemaController {
    private final CinemaService cinemaService;

    @GetMapping
    @PreAuthorize("permitAll()")
    public List<CinemaResponse> list(@RequestParam(required = false) String city) {
        return cinemaService.list(city);
    }

    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public CinemaResponse get(@PathVariable Long id) {
        return cinemaService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public CinemaResponse create(@Valid @RequestBody CinemaRequest request) {
        return cinemaService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CinemaResponse update(@PathVariable Long id, @Valid @RequestBody CinemaRequest request) {
        return cinemaService.update(id, request);
    }
}
