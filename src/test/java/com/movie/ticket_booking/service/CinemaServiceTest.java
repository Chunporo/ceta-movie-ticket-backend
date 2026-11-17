package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.CinemaRequest;
import com.movie.ticket_booking.dto.CinemaResponse;
import com.movie.ticket_booking.entity.Cinema;
import com.movie.ticket_booking.repository.CinemaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CinemaServiceTest {
    private CinemaRepository repo;
    private CinemaServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(CinemaRepository.class);
        service = new CinemaServiceImpl(repo);
        when(repo.save(any(Cinema.class))).thenAnswer(i -> {
            Cinema c = i.getArgument(0);
            if (c.getId() == null) c.setId(9L);
            return c;
        });
        when(repo.findIdsWithImaxRoom()).thenReturn(Set.of(1L));
    }

    private static Cinema cinema(long id, String code) {
        return Cinema.builder().id(id).code(code).name("n" + id).city("HCM").address("a").build();
    }

    private static CinemaRequest request(String code, String city) {
        return new CinemaRequest(code, "CETA X", city, "1 Main St", null, null, null);
    }

    @Test
    void listMarksCinemasThatHaveAnImaxRoom() {
        when(repo.findByActiveTrueAndCityIgnoreCaseOrderByNameAsc("hcm")).thenReturn(List.of(cinema(1, "A"), cinema(2, "B")));

        List<CinemaResponse> result = service.list(" hcm ");

        assertThat(result).extracting(CinemaResponse::imax).containsExactly(true, false);
    }

    @Test
    void listWithoutACityReturnsEveryActiveCinema() {
        when(repo.findByActiveTrueOrderByCityAscNameAsc()).thenReturn(List.of(cinema(1, "A")));
        assertThat(service.list(null)).hasSize(1);
        assertThat(service.list("  ")).hasSize(1);
    }

    @Test
    void adminsSeeInactiveCinemasToo() {
        Cinema off = cinema(2, "B");
        off.setActive(false);
        when(repo.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(cinema(1, "A"), off));
        assertThat(service.listAll()).extracting(CinemaResponse::active).containsExactly(true, false);
    }

    @Test
    void unknownCinemaIs404() {
        when(repo.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(5L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void createStoresTheCityInUpperCaseAndIsActiveByDefault() {
        CinemaResponse created = service.create(request("NEW1", "hn"));
        assertThat(created.city()).isEqualTo("HN");
        assertThat(created.active()).isTrue();
    }

    @Test
    void aDuplicateCodeIs409() {
        when(repo.existsByCode("DUP")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request("DUP", "HCM")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(repo, never()).save(any());
    }

    @Test
    void updateCannotTakeAnotherCinemasCode() {
        when(repo.findById(1L)).thenReturn(Optional.of(cinema(1, "A")));
        when(repo.findByCode("B")).thenReturn(Optional.of(cinema(2, "B")));
        assertThatThrownBy(() -> service.update(1L, request("B", "HCM")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test
    void updateKeepingItsOwnCodeIsFineAndCanDeactivate() {
        when(repo.findById(1L)).thenReturn(Optional.of(cinema(1, "A")));
        when(repo.findByCode("A")).thenReturn(Optional.of(cinema(1, "A")));
        CinemaResponse updated = service.update(1L, new CinemaRequest("A", "Renamed", "HCM", "x", null, null, false));
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.active()).isFalse();
        assertThat(updated.imax()).isTrue();
    }
}
