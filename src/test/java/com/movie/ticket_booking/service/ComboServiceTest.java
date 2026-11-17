package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ComboRequest;
import com.movie.ticket_booking.dto.ComboResponse;
import com.movie.ticket_booking.entity.Combo;
import com.movie.ticket_booking.repository.ComboRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ComboServiceTest {
    private ComboRepository repo;
    private ComboServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ComboRepository.class);
        service = new ComboServiceImpl(repo);
        when(repo.save(any(Combo.class))).thenAnswer(i -> {
            Combo c = i.getArgument(0);
            if (c.getId() == null) c.setId(5L);
            return c;
        });
    }

    private static ComboRequest request(String code, long price, Boolean active) {
        return new ComboRequest(code, "Combo", "Combo EN", "d", "d en", null, price, active, 3);
    }

    @Test
    void listReturnsOnlyWhatTheRepositoryCallsActive() {
        when(repo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(Combo.builder().id(1L).code("A").name("A").price(1000L).build()));
        assertThat(service.list()).extracting(ComboResponse::code).containsExactly("A");
    }

    @Test
    void createIsActiveByDefaultAndKeepsTheOrder() {
        ComboResponse created = service.create(request("NEW", 59_000, null));
        assertThat(created.active()).isTrue();
        assertThat(created.sortOrder()).isEqualTo(3);
        assertThat(created.price()).isEqualTo(59_000L);
    }

    @Test
    void aDuplicateCodeIs409() {
        when(repo.existsByCode("DUP")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request("DUP", 1000, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(repo, never()).save(any());
    }

    @Test
    void updateCanRetireAComboAndChangeItsPrice() {
        Combo existing = Combo.builder().id(1L).code("A").name("A").price(1000L).build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.findByCode("A")).thenReturn(Optional.of(existing));

        ComboResponse updated = service.update(1L, request("A", 2000, false));

        assertThat(updated.active()).isFalse();
        assertThat(updated.price()).isEqualTo(2000L);
    }

    @Test
    void updateCannotTakeAnotherCombosCode() {
        when(repo.findById(1L)).thenReturn(Optional.of(Combo.builder().id(1L).code("A").name("A").price(1L).build()));
        when(repo.findByCode("B")).thenReturn(Optional.of(Combo.builder().id(2L).code("B").name("B").price(1L).build()));
        assertThatThrownBy(() -> service.update(1L, request("B", 1000, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test
    void unknownComboIs404() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(9L, request("A", 1000, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }
}
