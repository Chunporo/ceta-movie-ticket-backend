package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.ComboRequest;
import com.movie.ticket_booking.dto.ComboResponse;
import com.movie.ticket_booking.service.ComboService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/combos", "/api/v1/combos"})
@RequiredArgsConstructor
public class ComboController {
    private final ComboService comboService;

    @GetMapping
    @PreAuthorize("permitAll()")
    public List<ComboResponse> list() {
        return comboService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ComboResponse create(@Valid @RequestBody ComboRequest request) {
        return comboService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ComboResponse update(@PathVariable Long id, @Valid @RequestBody ComboRequest request) {
        return comboService.update(id, request);
    }
}
