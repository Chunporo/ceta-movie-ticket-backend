package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.CastMemberDto;
import com.movie.ticket_booking.dto.ExternalRatingDto;
import com.movie.ticket_booking.dto.ExternalRatingsRequest;
import com.movie.ticket_booking.dto.GenreResponse;
import com.movie.ticket_booking.dto.MovieRequest;
import com.movie.ticket_booking.dto.MovieResponse;
import com.movie.ticket_booking.entity.CastMember;
import com.movie.ticket_booking.entity.Genre;
import com.movie.ticket_booking.entity.Movie;
import com.movie.ticket_booking.repository.GenreRepository;
import com.movie.ticket_booking.repository.MovieRepository;
import com.movie.ticket_booking.repository.MovieReviewRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class MovieServiceImpl implements MovieService {

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;
    private final MovieReviewRepository reviews;
    private final com.movie.ticket_booking.repository.ShowtimeRepository showtimes;

    public MovieServiceImpl(MovieRepository movieRepository, GenreRepository genreRepository, MovieReviewRepository reviews,
                            com.movie.ticket_booking.repository.ShowtimeRepository showtimes) {
        this.reviews = reviews;
        this.showtimes = showtimes;
        this.movieRepository = movieRepository;
        this.genreRepository = genreRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovieResponse> getAll() {
        Map<UUID, MovieReviewRepository.Stat> stats = ratingStats();
        Map<UUID, Long> prices = fromPrices();
        return movieRepository.findAll().stream().map(m -> toResponse(m, stats.get(m.getId()), prices.get(m.getId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovieResponse> search(String q, String genre, com.movie.ticket_booking.entity.enums.MovieStatus status) {
        Map<UUID, MovieReviewRepository.Stat> stats = ratingStats();
        Map<UUID, Long> prices = fromPrices();
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();
        String genreKey = genre == null || genre.isBlank() ? null : genre.trim().toLowerCase();
        return movieRepository.findAll().stream()
                .filter(m -> status == null || m.getStatus() == status)
                .filter(m -> needle == null || (m.getTitle() != null && m.getTitle().toLowerCase().contains(needle)))
                .filter(m -> genreKey == null || m.getGenres().stream().anyMatch(g ->
                        genreKey.equals(String.valueOf(g.getSlug()).toLowerCase()) || genreKey.equals(String.valueOf(g.getName()).toLowerCase())))
                .map(m -> toResponse(m, stats.get(m.getId()), prices.get(m.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MovieResponse getById(UUID id) {
        Movie movie = movieRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
        return toResponse(movie);
    }

    @Override
    public MovieResponse create(MovieRequest request) {
        if (movieRepository.findBySlug(request.getSlug()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Movie slug already exists");
        }

        Movie movie = Movie.builder().build();
        applyRequest(movie, request);
        Movie saved = movieRepository.saveAndFlush(movie);
        return toResponse(saved);
    }

    @Override
    public MovieResponse update(UUID id, MovieRequest request) {
        Movie movie = movieRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));

        movieRepository.findBySlug(request.getSlug())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Movie slug already exists");
                });

        applyRequest(movie, request);
        Movie saved = movieRepository.saveAndFlush(movie);
        return toResponse(saved);
    }

    @Override
    public MovieResponse updateExternalRatings(UUID id, ExternalRatingsRequest r) {
        Movie movie = movieRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
        movie.setImdbId(blankToNull(r.getImdbId()));
        movie.setImdbRating(r.getImdbRating());
        movie.setImdbVotes(r.getImdbVotes());
        movie.setRottenTomatoesPct(r.getRottenTomatoesPct());
        movie.setMetascore(r.getMetascore());
        movie.setTmdbRating(r.getTmdbRating());
        movie.setTmdbVotes(r.getTmdbVotes());
        movie.setExternalRatingsAt(java.time.Instant.now());
        return toResponse(movieRepository.saveAndFlush(movie));
    }

    private static List<ExternalRatingDto> externalRatings(Movie m) {
        List<ExternalRatingDto> out = new ArrayList<>();
        if (m.getImdbRating() != null) out.add(new ExternalRatingDto("IMDB", m.getImdbRating(), 10, m.getImdbVotes()));
        if (m.getRottenTomatoesPct() != null) out.add(new ExternalRatingDto("ROTTEN_TOMATOES", java.math.BigDecimal.valueOf(m.getRottenTomatoesPct()), 100, null));
        if (m.getMetascore() != null) out.add(new ExternalRatingDto("METACRITIC", java.math.BigDecimal.valueOf(m.getMetascore()), 100, null));
        if (m.getTmdbRating() != null) out.add(new ExternalRatingDto("TMDB", m.getTmdbRating(), 10, m.getTmdbVotes()));
        return out;
    }

    @Override
    public void delete(UUID id) {
        if (!movieRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }
        movieRepository.deleteById(id);
    }

    private void applyRequest(Movie movie, MovieRequest request) {
        movie.setTitle(request.getTitle());
        movie.setSlug(request.getSlug());
        movie.setPosterUrl(request.getPosterUrl());
        movie.setAgeRating(request.getAgeRating());
        movie.setStatus(request.getStatus());
        movie.setDuration(request.getDuration());
        movie.setReleaseDate(request.getReleaseDate());
        movie.setTrailerUrl(request.getTrailerUrl());
        movie.setDirector(blankToNull(request.getDirector()));
        movie.setDirectorPhotoUrl(blankToNull(request.getDirectorPhotoUrl()));
        movie.setBackdropUrl(blankToNull(request.getBackdropUrl()));
        movie.setLanguage(request.getLanguage());
        movie.setDescription(request.getDescription());
        movie.setRatingScore(request.getRatingScore());

        List<CastMemberDto> members = Optional.ofNullable(request.getCastMembers()).orElseGet(List::of);
        if (members.isEmpty()) {
            members = Optional.ofNullable(request.getCast()).orElseGet(List::of).stream()
                    .map(n -> CastMemberDto.builder().name(n).build()).toList();
        }
        movie.setCast(members.stream()
                .map(m -> new CastMember(m.getName(), blankToNull(m.getRole()), blankToNull(m.getPhotoUrl())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        movie.setFormats(new HashSet<>(Optional.ofNullable(request.getFormats()).orElseGet(Set::of)));

        List<Long> genreIds = Optional.ofNullable(request.getGenreIds()).orElseGet(List::of);
        if (genreIds.isEmpty()) {
            movie.setGenres(new HashSet<>());
        } else {
            List<Genre> genres = genreRepository.findAllById(genreIds);
            Set<Long> found = genres.stream().map(Genre::getId).collect(Collectors.toSet());
            List<Long> missing = genreIds.stream().filter(id -> !found.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown genreIds: " + missing);
            }
            movie.setGenres(new HashSet<>(genres));
        }
    }

    private Map<UUID, MovieReviewRepository.Stat> ratingStats() {
        Map<UUID, MovieReviewRepository.Stat> stats = new HashMap<>();
        reviews.statsForAllMovies().forEach(s -> stats.put(s.getMovieId(), s));
        return stats;
    }

    private Map<UUID, Long> fromPrices() {
        Map<UUID, Long> prices = new HashMap<>();
        showtimes.minPricesByMovie(java.time.Instant.now()).forEach(p -> prices.put(p.getMovieId(), p.getMinPrice().longValue()));
        return prices;
    }

    private MovieResponse toResponse(Movie movie) {
        Long price = showtimes.minPricesByMovie(java.time.Instant.now()).stream()
                .filter(p -> p.getMovieId().equals(movie.getId())).map(p -> p.getMinPrice().longValue()).findFirst().orElse(null);
        return toResponse(movie, reviews.statFor(movie.getId()).orElse(null), price);
    }

    private MovieResponse toResponse(Movie movie, MovieReviewRepository.Stat stat, Long fromPrice) {
        List<GenreResponse> genreResponses = movie.getGenres().stream()
                .sorted(Comparator.comparing(Genre::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(g -> GenreResponse.builder().id(g.getId()).name(g.getName()).slug(g.getSlug()).build())
                .toList();

        return MovieResponse.builder()
                .id(movie.getId())
                .title(movie.getTitle())
                .slug(movie.getSlug())
                .posterUrl(movie.getPosterUrl())
                .ageRating(movie.getAgeRating())
                .status(movie.getStatus())
                .duration(movie.getDuration())
                .releaseDate(movie.getReleaseDate())
                .trailerUrl(movie.getTrailerUrl())
                .director(movie.getDirector())
                .directorPhotoUrl(movie.getDirectorPhotoUrl())
                .backdropUrl(movie.getBackdropUrl())
                .language(movie.getLanguage())
                .description(movie.getDescription())
                .ratingScore(movie.getRatingScore())
                .ratingAverage(stat == null || stat.getAverage() == null ? null : Math.round(stat.getAverage() * 10) / 10.0)
                .ratingCount(stat == null || stat.getCount() == null ? 0 : stat.getCount())
                .imdbId(movie.getImdbId())
                .externalRatings(externalRatings(movie))
                .fromPrice(fromPrice)
                .cast(movie.getCast().stream().map(CastMember::getName).collect(java.util.stream.Collectors.toCollection(ArrayList::new)))
                .castMembers(movie.getCast().stream()
                        .map(c -> new CastMemberDto(c.getName(), c.getRole(), c.getPhotoUrl())).toList())
                .formats(new HashSet<>(movie.getFormats()))
                .genres(genreResponses)
                .createdAt(movie.getCreatedAt())
                .updatedAt(movie.getUpdatedAt())
                .build();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
