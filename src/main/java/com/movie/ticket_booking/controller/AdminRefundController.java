package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.RefundNoteRequest;
import com.movie.ticket_booking.dto.booking.RefundResponse;
import com.movie.ticket_booking.entity.enums.RefundStatus;
import com.movie.ticket_booking.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {
    private final RefundService refunds;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<RefundResponse> list(@RequestParam(required = false) RefundStatus status) {
        return refunds.list(status);
    }

    @PutMapping("/{id}/done")
    @PreAuthorize("hasRole('ADMIN')")
    public RefundResponse done(@PathVariable Long id, @RequestBody(required = false) RefundNoteRequest body) {
        return refunds.markDone(id, body == null ? null : body.note());
    }

    @PutMapping("/{id}/failed")
    @PreAuthorize("hasRole('ADMIN')")
    public RefundResponse failed(@PathVariable Long id, @RequestBody(required = false) RefundNoteRequest body) {
        return refunds.markFailed(id, body == null ? null : body.note());
    }
}
