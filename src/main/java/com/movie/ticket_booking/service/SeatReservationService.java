package com.movie.ticket_booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;

/**
 * Manages temporary seat holds.
 * Every Redis call is individually wrapped in try/catch.
 * Falls back to an in-memory ConcurrentHashMap when Redis is unavailable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatReservationService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.booking.hold-ttl-seconds:60}")
    private long holdTtlSeconds;

    /** In-memory fallback — no TTL enforcement other than expiry check */
    private final Map<String, Long> inMemoryFallback = new ConcurrentHashMap<>();

    private static final String SEAT_HOLD_KEY_PREFIX = "seat:hold:";

    // ── Public API ───────────────────────────────────────────────────────────

    public boolean holdSeats(Long showtimeId, List<Long> seatIds, String userId) {
        try {
            String keyPrefix = getKeyPrefix(showtimeId);
            for (Long seatId : seatIds) {
                if (Boolean.TRUE.equals(redisTemplate.hasKey(keyPrefix + seatId))) {
                    return false;
                }
            }
            for (Long seatId : seatIds) {
                redisTemplate.opsForValue().set(
                        keyPrefix + seatId, userId,
                        holdTtlSeconds, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.warn("Redis unavailable (holdSeats), using in-memory fallback: {}", e.getMessage());
            return holdInMemory(showtimeId, seatIds, userId);
        }
    }

    public void releaseSeats(Long showtimeId, List<Long> seatIds) {
        try {
            String keyPrefix = getKeyPrefix(showtimeId);
            List<String> keys = seatIds.stream()
                    .map(id -> keyPrefix + id)
                    .collect(Collectors.toList());
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("Redis unavailable (releaseSeats), using in-memory fallback: {}", e.getMessage());
            releaseInMemory(showtimeId, seatIds);
        }
    }

    public Set<Long> getHeldSeatIds(Long showtimeId) {
        try {
            String pattern = getKeyPrefix(showtimeId) + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty())
                return Set.of();
            return keys.stream()
                    .map(key -> {
                        try {
                            return Long.parseLong(key.substring(key.lastIndexOf(":") + 1));
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Redis unavailable (getHeldSeatIds), using in-memory fallback: {}", e.getMessage());
            return getHeldInMemory(showtimeId);
        }
    }

    /** Returns only the seat IDs held by the specific userId (email). */
    public Set<Long> getHeldSeatIdsByUser(Long showtimeId, String userId) {
        try {
            String pattern = getKeyPrefix(showtimeId) + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty())
                return Set.of();
            Set<Long> result = new HashSet<>();
            for (String key : keys) {
                Object owner = redisTemplate.opsForValue().get(key);
                if (userId.equals(owner)) {
                    try {
                        result.add(Long.parseLong(key.substring(key.lastIndexOf(":") + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Redis unavailable (getHeldSeatIdsByUser), using in-memory fallback: {}", e.getMessage());
            return getHeldByUserInMemory(showtimeId, userId);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getKeyPrefix(Long showtimeId) {
        return SEAT_HOLD_KEY_PREFIX + showtimeId + ":";
    }

    // ── In-memory fallback ───────────────────────────────────────────────────

    private boolean holdInMemory(Long showtimeId, List<Long> seatIds, String userId) {
        for (Long seatId : seatIds) {
            String key = showtimeId + ":" + seatId;
            Long exp = inMemoryFallback.get(key);
            if (exp != null && exp > System.currentTimeMillis())
                return false;
        }
        long expiresAt = System.currentTimeMillis() + holdTtlSeconds * 1_000L;
        seatIds.forEach(id -> inMemoryFallback.put(showtimeId + ":" + id, expiresAt));
        return true;
    }

    private void releaseInMemory(Long showtimeId, List<Long> seatIds) {
        seatIds.forEach(id -> inMemoryFallback.remove(showtimeId + ":" + id));
    }

    private Set<Long> getHeldInMemory(Long showtimeId) {
        long now = System.currentTimeMillis();
        String prefix = showtimeId + ":";
        return inMemoryFallback.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && e.getValue() > now)
                .map(e -> Long.parseLong(e.getKey().substring(prefix.length())))
                .collect(Collectors.toSet());
    }

    /** In-memory fallback: no owner tracking — returns empty (conservative). */
    private Set<Long> getHeldByUserInMemory(Long showtimeId, String userId) {
        // In-memory fallback stores no owner info; return empty to avoid false
        // HELD_BY_ME
        return Set.of();
    }
}