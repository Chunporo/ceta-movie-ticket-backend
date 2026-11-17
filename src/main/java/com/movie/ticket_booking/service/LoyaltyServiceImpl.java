package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.LoyaltyAdjustRequest;
import com.movie.ticket_booking.dto.LoyaltySummaryResponse;
import com.movie.ticket_booking.entity.LoyaltyEntry;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.LoyaltyReason;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class LoyaltyServiceImpl implements LoyaltyService {
    private final UserRepository users;
    private final LoyaltyEntryRepository entries;
    private final TicketRepository tickets;
    private final UserVoucherRepository vouchers;
    private final BookingExtrasCalculator calculator;
    private final long vipThreshold;

    public LoyaltyServiceImpl(UserRepository users, LoyaltyEntryRepository entries,
                              TicketRepository tickets, UserVoucherRepository vouchers,
                              BookingExtrasCalculator calculator, @Value("${app.loyalty.vip-threshold:5000}") long vipThreshold) {
        this.users = users;
        this.entries = entries;
        this.tickets = tickets;
        this.vouchers = vouchers;
        this.calculator = calculator;
        this.vipThreshold = vipThreshold;
    }

    @Override
    @Transactional(readOnly = true)
    public LoyaltySummaryResponse summary(String requesterEmail) {
        return summaryOf(findUser(requesterEmail));
    }

    @Override
    @Transactional
    public LoyaltySummaryResponse adjust(LoyaltyAdjustRequest request) {
        // The same lock spending takes, so a correction and a booking cannot both rely on the same balance.
        User user = users.findByEmailForUpdate(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (request.points() == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "points must not be 0");
        }
        if (entries.balance(user.getId(), 0L) + request.points() < 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "The balance cannot go below zero");
        }
        entries.save(LoyaltyEntry.builder().userId(user.getId()).delta(request.points()).reason(LoyaltyReason.ADJUSTMENT)
                .note(request.note()).createdAt(Instant.now()).build());
        return summaryOf(user);
    }

    private LoyaltySummaryResponse summaryOf(User user) {
        long balance = entries.balance(user.getId(), 0L);
        long lifetime = entries.lifetimeEarned(user.getId());
        boolean vip = lifetime >= vipThreshold;
        return new LoyaltySummaryResponse(balance, lifetime, vip ? "VIP" : "MEMBER", vip ? null : "VIP",
                vip ? 0 : vipThreshold - lifetime, calculator.redeemBlockPoints(), calculator.redeemBlockVnd(),
                new LoyaltySummaryResponse.Stats(tickets.countWatchedBy(user.getId(), Instant.now()),
                        vouchers.countByIdUserId(user.getId())));
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
