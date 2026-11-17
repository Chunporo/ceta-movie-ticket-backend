package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.Cinema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface CinemaRepository extends JpaRepository<Cinema, Long> {
    Optional<Cinema> findByCode(String code);

    boolean existsByCode(String code);

    List<Cinema> findByActiveTrueOrderByCityAscNameAsc();

    List<Cinema> findByActiveTrueAndCityIgnoreCaseOrderByNameAsc(String city);

    Optional<Cinema> findFirstByActiveTrueOrderByIdAsc();

    /** Ids of cinemas that own at least one IMAX room, so the app can show the IMAX badge. */
    @Query("select distinct r.cinema.id from Room r where r.roomType = com.movie.ticket_booking.entity.enums.RoomType.IMAX and r.cinema is not null")
    Set<Long> findIdsWithImaxRoom();
}
