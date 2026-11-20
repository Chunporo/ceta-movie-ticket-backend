package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ExternalRatingsRequest;
import com.movie.ticket_booking.dto.MovieRequest;
import com.movie.ticket_booking.dto.MovieResponse;
import com.movie.ticket_booking.entity.enums.AgeRating;
import com.movie.ticket_booking.entity.enums.MovieStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Scores from other sites sit beside the customers' score, are replaced as a whole and are never touched by a normal movie update. */
@DataJpaTest(showSql = false)
@Import(MovieServiceImpl.class)
class MovieExternalRatingsTest {
    @Autowired private MovieServiceImpl movies;

    private MovieRequest request(String slug) {
        return MovieRequest.builder().title("Ext").slug(slug).posterUrl("https://example.invalid/p.jpg")
                .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(100).build();
    }

    @Test
    void importedScoresAreListedBySourceAndSkipMissingOnes() {
        MovieResponse m = movies.create(request("ext-" + UUID.randomUUID()));
        assertTrue(m.getExternalRatings().isEmpty());

        MovieResponse r = movies.updateExternalRatings(m.getId(), ExternalRatingsRequest.builder()
                .imdbId("tt1").imdbRating(new BigDecimal("7.6")).imdbVotes(1234L).rottenTomatoesPct(87).tmdbRating(new BigDecimal("7.8")).tmdbVotes(900L).build());

        assertEquals("tt1", r.getImdbId());
        assertEquals(List.of("IMDB", "ROTTEN_TOMATOES", "TMDB"), r.getExternalRatings().stream().map(x -> x.source()).toList());
        assertEquals(1234L, r.getExternalRatings().get(0).votes());
        assertEquals(100, r.getExternalRatings().get(1).scale());
        assertNull(r.getRatingAverage(), "the customers' score is untouched");
    }

    @Test
    void aNormalUpdateKeepsThemAndANewImportClearsWhatIsMissing() {
        MovieResponse m = movies.create(request("ext2-" + UUID.randomUUID()));
        movies.updateExternalRatings(m.getId(), ExternalRatingsRequest.builder().imdbRating(new BigDecimal("8.0")).metascore(70).build());

        MovieResponse after = movies.update(m.getId(), request(m.getSlug()));
        assertEquals(2, after.getExternalRatings().size());

        MovieResponse cleared = movies.updateExternalRatings(m.getId(), ExternalRatingsRequest.builder().metascore(65).build());
        assertEquals(List.of("METACRITIC"), cleared.getExternalRatings().stream().map(x -> x.source()).toList());
    }

    @Test
    void aBackdropIsStoredAndABlankOneBecomesNull() {
        MovieResponse m = movies.create(MovieRequest.builder().title("Wide").slug("wide-" + UUID.randomUUID()).posterUrl("https://example.invalid/p.jpg")
                .backdropUrl("https://example.invalid/b.jpg").ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(100).build());
        assertEquals("https://example.invalid/b.jpg", m.getBackdropUrl());
        assertNull(movies.update(m.getId(), request(m.getSlug())).getBackdropUrl());
    }
}
