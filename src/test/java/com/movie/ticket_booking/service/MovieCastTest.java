package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.CastMemberDto;
import com.movie.ticket_booking.dto.MovieRequest;
import com.movie.ticket_booking.dto.MovieResponse;
import com.movie.ticket_booking.entity.enums.AgeRating;
import com.movie.ticket_booking.entity.enums.MovieStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Cast members carry a character and a photo; names-only requests (older clients) keep working. */
@DataJpaTest(showSql = false)
@Import(MovieServiceImpl.class)
class MovieCastTest {
    @Autowired private MovieServiceImpl movies;
    @Autowired private TestEntityManager em;

    private MovieRequest.MovieRequestBuilder base() {
        return MovieRequest.builder().title("Cast test").slug("cast-" + UUID.randomUUID())
                .posterUrl("https://example.invalid/p.jpg").ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(100);
    }

    @Test
    void castMembersKeepRolePhotoAndOrder() {
        MovieResponse created = movies.create(base().castMembers(List.of(
                new CastMemberDto("Ketsu", "Bao Chung", "https://img.example/ketsu.jpg"),
                new CastMemberDto("On Vu", "  ", null))).build());
        em.flush();
        em.clear();

        MovieResponse read = movies.getById(created.getId());
        assertEquals(List.of("Ketsu", "On Vu"), read.getCast());
        assertEquals("Bao Chung", read.getCastMembers().get(0).getRole());
        assertEquals("https://img.example/ketsu.jpg", read.getCastMembers().get(0).getPhotoUrl());
        assertNull(read.getCastMembers().get(1).getRole(), "a blank role is stored as null");
        assertNull(read.getCastMembers().get(1).getPhotoUrl());
    }

    @Test
    void directorPhotoIsStoredAndABlankDirectorBecomesNull() {
        MovieResponse withPhoto = movies.create(base().director("Tommy Wirkola").directorPhotoUrl("https://img.example/d.jpg").build());
        MovieResponse blank = movies.create(base().director("  ").build());
        em.flush();
        em.clear();
        assertEquals("https://img.example/d.jpg", movies.getById(withPhoto.getId()).getDirectorPhotoUrl());
        assertNull(movies.getById(blank.getId()).getDirector());
        assertNull(movies.getById(blank.getId()).getDirectorPhotoUrl());
    }

    @Test
    void namesOnlyRequestStillWorks() {
        MovieResponse created = movies.create(base().cast(List.of("A", "B")).build());
        em.flush();
        em.clear();

        MovieResponse read = movies.getById(created.getId());
        assertEquals(List.of("A", "B"), read.getCast());
        assertEquals(2, read.getCastMembers().size());
        assertNull(read.getCastMembers().get(0).getPhotoUrl());
    }
}
