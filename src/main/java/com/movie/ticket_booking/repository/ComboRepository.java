package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.Combo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComboRepository extends JpaRepository<Combo, Long> {
    List<Combo> findByActiveTrueOrderBySortOrderAscIdAsc();

    Optional<Combo> findByCode(String code);

    boolean existsByCode(String code);
}
