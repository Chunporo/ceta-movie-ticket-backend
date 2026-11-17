package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ComboRequest;
import com.movie.ticket_booking.dto.ComboResponse;
import com.movie.ticket_booking.entity.Combo;
import com.movie.ticket_booking.repository.ComboRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ComboServiceImpl implements ComboService {
    private final ComboRepository combos;

    @Override
    @Transactional(readOnly = true)
    public List<ComboResponse> list() {
        return combos.findByActiveTrueOrderBySortOrderAscIdAsc().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public ComboResponse create(ComboRequest request) {
        if (combos.existsByCode(request.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Combo code already exists");
        }
        return toResponse(combos.save(apply(new Combo(), request)));
    }

    @Override
    @Transactional
    public ComboResponse update(Long id, ComboRequest request) {
        Combo combo = combos.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Combo not found"));
        combos.findByCode(request.code()).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Combo code already exists");
        });
        // A price change never reaches bookings already made: they hold their own copy (booking_combo).
        return toResponse(combos.save(apply(combo, request)));
    }

    private Combo apply(Combo c, ComboRequest r) {
        c.setCode(r.code());
        c.setName(r.name());
        c.setNameEn(r.nameEn());
        c.setDescription(r.description());
        c.setDescriptionEn(r.descriptionEn());
        c.setImageUrl(r.imageUrl());
        c.setPrice(r.price());
        if (r.active() != null) c.setActive(r.active());
        if (r.sortOrder() != null) c.setSortOrder(r.sortOrder());
        return c;
    }

    private ComboResponse toResponse(Combo c) {
        return new ComboResponse(c.getId(), c.getCode(), c.getName(), c.getNameEn(), c.getDescription(), c.getDescriptionEn(),
                c.getImageUrl(), c.getPrice(), c.isActive(), c.getSortOrder());
    }
}
