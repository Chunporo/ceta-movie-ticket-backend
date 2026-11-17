package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.MovieReminder;
import com.movie.ticket_booking.entity.MovieReminderId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MovieReminderRepository extends JpaRepository<MovieReminder, MovieReminderId> {
    List<MovieReminder> findByIdUserIdOrderByCreatedAtDesc(Long userId);

    List<MovieReminder> findByIdMovieId(UUID movieId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MovieReminder r where r.id.movieId = :movieId")
    int deleteAllForMovie(@Param("movieId") UUID movieId);
}
