package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.CinemaRequest;
import com.movie.ticket_booking.dto.CinemaResponse;
import com.movie.ticket_booking.entity.Cinema;
import com.movie.ticket_booking.repository.CinemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CinemaServiceImpl implements CinemaService {
    private final CinemaRepository cinemas;

    @Override
    @Transactional(readOnly = true)
    public List<CinemaResponse> list(String city) {
        List<Cinema> found = city == null || city.isBlank()
                ? cinemas.findByActiveTrueOrderByCityAscNameAsc()
                : cinemas.findByActiveTrueAndCityIgnoreCaseOrderByNameAsc(city.trim());
        Set<Long> withImax = cinemas.findIdsWithImaxRoom();
        return found.stream().map(c -> toResponse(c, withImax.contains(c.getId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CinemaResponse> listAll() {
        Set<Long> withImax = cinemas.findIdsWithImaxRoom();
        return cinemas.findAll(org.springframework.data.domain.Sort.by("city", "name")).stream()
                .map(c -> toResponse(c, withImax.contains(c.getId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CinemaResponse get(Long id) {
        Cinema cinema = cinemas.findById(id).orElseThrow(this::notFound);
        return toResponse(cinema, cinemas.findIdsWithImaxRoom().contains(id));
    }

    @Override
    @Transactional
    public CinemaResponse create(CinemaRequest request) {
        if (cinemas.existsByCode(request.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cinema code already exists");
        }
        Cinema saved = cinemas.save(apply(new Cinema(), request));
        return toResponse(saved, false);
    }

    @Override
    @Transactional
    public CinemaResponse update(Long id, CinemaRequest request) {
        Cinema cinema = cinemas.findById(id).orElseThrow(this::notFound);
        cinemas.findByCode(request.code()).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cinema code already exists");
        });
        Cinema saved = cinemas.save(apply(cinema, request));
        return toResponse(saved, cinemas.findIdsWithImaxRoom().contains(id));
    }

    private Cinema apply(Cinema cinema, CinemaRequest r) {
        cinema.setCode(r.code());
        cinema.setName(r.name());
        cinema.setCity(r.city().toUpperCase(Locale.ROOT));
        cinema.setAddress(r.address());
        cinema.setLatitude(r.latitude());
        cinema.setLongitude(r.longitude());
        if (r.active() != null) cinema.setActive(r.active());
        return cinema;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cinema not found");
    }

    private CinemaResponse toResponse(Cinema c, boolean imax) {
        return new CinemaResponse(c.getId(), c.getCode(), c.getName(), c.getCity(), c.getAddress(),
                c.getLatitude(), c.getLongitude(), imax, c.isActive());
    }
}
