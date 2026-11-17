package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.MovieReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MovieReviewRepository extends JpaRepository<MovieReview, Long> {
    Optional<MovieReview> findByMovieIdAndUserId(UUID movieId, Long userId);

    Page<MovieReview> findByMovieIdOrderByIdDesc(UUID movieId, Pageable pageable);

    long countByUserId(Long userId);

    interface Stat {
        UUID getMovieId();

        Double getAverage();

        Long getCount();
    }

    @Query("select r.movieId as movieId, avg(r.rating) as average, count(r) as count from MovieReview r group by r.movieId")
    List<Stat> statsForAllMovies();

    @Query("select r.movieId as movieId, avg(r.rating) as average, count(r) as count from MovieReview r where r.movieId = :movieId group by r.movieId")
    Optional<Stat> statFor(@Param("movieId") UUID movieId);
}
