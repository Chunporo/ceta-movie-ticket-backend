package com.movie.ticket_booking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.movie.ticket_booking.dto.MovieRequest;
import com.movie.ticket_booking.dto.MovieResponse;
import com.movie.ticket_booking.service.MovieService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import com.movie.ticket_booking.entity.enums.MovieStatus;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/movies", "/api/v1/movies"})
@Slf4j
public class MovieController {
    
    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
    @PreAuthorize("permitAll()")
    public ResponseEntity<List<MovieResponse>> getAllMovies(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) MovieStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/movies q={} genre={} status={} page={}", q, genre, status, page);
        List<MovieResponse> all = movieService.search(q, genre, status);
        List<MovieResponse> result = all;
        if (page != null) { // 0-based; the full match count travels in X-Total-Count so the body stays a plain list
            int from = Math.min(Math.max(page, 0) * Math.max(size, 1), all.size());
            result = all.subList(from, Math.min(from + Math.max(size, 1), all.size()));
        }
        log.info("GET /api/movies -> {} item(s)", result.size());
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(all.size())).body(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public MovieResponse getMovieById(@PathVariable UUID id) {
        log.info("GET /api/movies/{}", id);
        MovieResponse result = movieService.getById(id);
        log.info("GET /api/movies/{} -> slug={}", id, result.getSlug());
        return result;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MovieResponse> createMovie(@Valid @RequestBody MovieRequest request) {
        log.info(
                "POST /api/movies slug={} title={} genreIds={} formats={} cast={}",
                request.getSlug(),
                request.getTitle(),
                request.getGenreIds() == null ? 0 : request.getGenreIds().size(),
                request.getFormats() == null ? 0 : request.getFormats().size(),
                request.getCast() == null ? 0 : request.getCast().size()
        );
        MovieResponse created = movieService.create(request);
        log.info("POST /api/movies -> 201 id={} slug={}", created.getId(), created.getSlug());
        return ResponseEntity.created(URI.create("/api/movies/" + created.getId())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MovieResponse updateMovie(@PathVariable UUID id, @Valid @RequestBody MovieRequest request) {
        log.info(
                "PUT /api/movies/{} slug={} title={} genreIds={} formats={} cast={}",
                id,
                request.getSlug(),
                request.getTitle(),
                request.getGenreIds() == null ? 0 : request.getGenreIds().size(),
                request.getFormats() == null ? 0 : request.getFormats().size(),
                request.getCast() == null ? 0 : request.getCast().size()
        );
        MovieResponse updated = movieService.update(id, request);
        log.info("PUT /api/movies/{} -> 200 slug={}", id, updated.getSlug());
        return updated;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteMovie(@PathVariable UUID id) {
        log.info("DELETE /api/movies/{}", id);
        movieService.delete(id);
        log.info("DELETE /api/movies/{} -> 204", id);
        return ResponseEntity.noContent().build();
    }
}