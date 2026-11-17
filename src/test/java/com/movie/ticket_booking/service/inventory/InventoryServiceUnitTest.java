package com.movie.ticket_booking.service.inventory;

import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import com.movie.ticket_booking.repository.ShowtimeSeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceUnitTest {
    @Mock private ShowtimeRepository showtimes;
    @Mock private ShowtimeSeatRepository seats;
    @Mock private org.springframework.context.ApplicationEventPublisher events;
    @InjectMocks private InventoryServiceImpl inventory;

    @Test
    void claimLocksShowtimeBeforeReadingOrUpdatingAvailability() {
        Instant expiry = Instant.now().plusSeconds(600);
        when(showtimes.lockStatusForInventory(1L)).thenReturn(Optional.of("SCHEDULED"));
        when(seats.countAvailable(1L, List.of(7L, 8L))).thenReturn(2L);
        when(seats.claimAvailable(eq(1L), eq(List.of(7L, 8L)), eq(2L), eq(expiry), any())).thenReturn(2);
        assertEquals(List.of(), inventory.claim(1L, List.of(7L, 8L), 2L, expiry));
        var order = inOrder(showtimes, seats);
        order.verify(showtimes).lockStatusForInventory(1L);
        order.verify(seats).countAvailable(1L, List.of(7L, 8L));
        order.verify(seats).claimAvailable(eq(1L), eq(List.of(7L, 8L)), eq(2L), eq(expiry), any());
    }

    @Test
    void shortConditionalUpdateFailsInsteadOfReturningSuccess() {
        Instant expiry = Instant.now().plusSeconds(600);
        when(showtimes.lockStatusForInventory(1L)).thenReturn(Optional.of("SCHEDULED"));
        when(seats.countAvailable(1L, List.of(7L, 8L))).thenReturn(2L);
        when(seats.claimAvailable(eq(1L), eq(List.of(7L, 8L)), eq(2L), eq(expiry), any())).thenReturn(1);
        assertThrows(SeatConflictException.class, () -> inventory.claim(1L, List.of(7L, 8L), 2L, expiry));
    }

    @Test
    void availabilityConflictDoesNotAttemptAnUpdate() {
        Instant expiry = Instant.now().plusSeconds(600);
        when(showtimes.lockStatusForInventory(1L)).thenReturn(Optional.of("SCHEDULED"));
        when(seats.countAvailable(1L, List.of(7L, 8L))).thenReturn(1L);
        when(seats.findAvailableSeatIds(1L, List.of(7L, 8L))).thenReturn(List.of(7L));
        SeatConflictException conflict = assertThrows(SeatConflictException.class,
                () -> inventory.claim(1L, List.of(7L, 8L), 2L, expiry));
        assertEquals(List.of(8L), conflict.getConflictSeatIds());
        verify(seats, never()).claimAvailable(any(), anyList(), any(), any(), any());
    }
}
