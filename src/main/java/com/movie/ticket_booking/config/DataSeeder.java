package com.movie.ticket_booking.config;

import com.movie.ticket_booking.entity.Room;
import com.movie.ticket_booking.entity.Seat;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.entity.Movie;
import com.movie.ticket_booking.entity.Genre;
import com.movie.ticket_booking.entity.Role;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.AgeRating;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.MovieStatus;
import com.movie.ticket_booking.entity.enums.RoleName;
import com.movie.ticket_booking.entity.enums.RoomType;
import com.movie.ticket_booking.entity.enums.SeatType;
import com.movie.ticket_booking.repository.GenreRepository;
import com.movie.ticket_booking.repository.MovieRepository;
import com.movie.ticket_booking.repository.RoleRepository;
import com.movie.ticket_booking.repository.RoomRepository;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import com.movie.ticket_booking.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class DataSeeder implements ApplicationRunner {

    private final GenreRepository genreRepository;
    private final MovieRepository movieRepository;
    private final RoomRepository roomRepository;
    private final ShowtimeRepository showtimeRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(
            GenreRepository genreRepository,
            MovieRepository movieRepository,
            RoomRepository roomRepository,
            ShowtimeRepository showtimeRepository,
            RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.genreRepository = genreRepository;
        this.movieRepository = movieRepository;
        this.roomRepository = roomRepository;
        this.showtimeRepository = showtimeRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedRoles();
        seedAdminUser();
        seedGenres();
        seedMovies();
        seedRooms();
        seedShowtimes();
    }

    private void seedRoles() {
        for (RoleName roleName : RoleName.values()) {
            roleRepository.findByName(roleName)
                    .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).build()));
        }
    }

    private void seedAdminUser() {
        String adminEmail = "admin@gmail.com";
        String adminUsername = "admin";
        String adminPassword = "admin@123";

        Role adminRole = roleRepository.findByName(RoleName.ADMIN)
                .orElseGet(() -> roleRepository.save(Role.builder().name(RoleName.ADMIN).build()));

        userRepository.findByEmail(adminEmail).ifPresentOrElse(existing -> {
            if (!existing.getRoles().contains(adminRole)) {
                existing.getRoles().add(adminRole);
                userRepository.save(existing);
            }
        }, () -> {
            User admin = User.builder()
                    .username(adminUsername)
                    .email(adminEmail)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .enabled(true)
                    .roles(Set.of(adminRole))
                    .build();
            userRepository.save(admin);
        });
    }

    private void seedGenres() {
        List<GenreSeed> seeds = List.of(
                new GenreSeed("Hành động", "hanh-dong"),
                new GenreSeed("Hài", "hai"),
                new GenreSeed("Tâm lý", "tam-ly"),
                new GenreSeed("Kinh dị", "kinh-di"),
                new GenreSeed("Khoa học viễn tưởng", "sci-fi"),
                new GenreSeed("Hoạt hình", "hoat-hinh"),
                new GenreSeed("Võ thuật", "vo-thuat"));

        for (GenreSeed seed : seeds) {
            genreRepository.findBySlug(seed.slug())
                    .orElseGet(() -> genreRepository.save(Genre.builder().name(seed.name()).slug(seed.slug()).build()));
        }
    }

    private record GenreSeed(String name, String slug) {
    }

    private void seedMovies() {
        List<MovieSeed> seeds = List.of(
                new MovieSeed(
                        "Mortal Kombat 2: Cuộc Chiến Sinh Tử",
                        "mortal-kombat-2-cuoc-chien-sinh-tu",
                        "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=500&q=80",
                        AgeRating.T18,
                        MovieStatus.NOW_SHOWING,
                        Set.of(MovieFormat.IMAX, MovieFormat.TWO_D),
                        116,
                        LocalDate.of(2026, 5, 8),
                        "https://www.youtube.com/watch?v=v7O8Z0vY1sc",
                        "Đấu trường đẫm máu Mortal Kombat chính thức quay trở lại với cuộc chiến tàn khốc giữa các chiến binh Địa giới và Ngoại giới, có sự xuất hiện của Johnny Cage.",
                        new BigDecimal("4.6"),
                        List.of("Karl Urban", "Adeline Rudolph", "Lewis Tan", "Jessica McNamee"),
                        List.of("hanh-dong", "vo-thuat", "sci-fi")),
                new MovieSeed(
                        "Star Wars: The Mandalorian & Grogu",
                        "star-wars-the-mandalorian-and-grogu",
                        "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?auto=format&fit=crop&w=500&q=80",
                        AgeRating.T13,
                        MovieStatus.NOW_SHOWING,
                        Set.of(MovieFormat.IMAX, MovieFormat.THREE_D, MovieFormat.TWO_D),
                        125,
                        LocalDate.of(2026, 5, 20),
                        "https://www.youtube.com/watch?v=aXOChLn58u4",
                        "Hành trình điện ảnh hoàn toàn mới của tay săn tiền thưởng Din Djarin và đứa trẻ Grogu ngoài rìa thiên hà đầy rẫy hiểm nguy.",
                        new BigDecimal("4.9"),
                        List.of("Pedro Pascal", "Sigourney Weaver", "Jeremy Allen White"),
                        List.of("sci-fi", "hanh-dong")),
                new MovieSeed(
                        "Doraemon Movie 45: Nobita Và Lâu Đài Dưới Đáy Biển",
                        "doraemon-movie-45-nobita-va-lau-dai-duoi-day-bien",
                        "https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=500&q=80",
                        AgeRating.P,
                        MovieStatus.NOW_SHOWING,
                        Set.of(MovieFormat.TWO_D),
                        108,
                        LocalDate.of(2026, 5, 22),
                        "https://www.youtube.com/watch?v=fSIdPkaV_Xo",
                        "Phiên bản điện ảnh thứ 45 đưa nhóm bạn Doraemon, Nobita thám hiểm đại dương sâu thẳm và khám phá bí ẩn cốt lõi về một lâu đài cổ bị chìm.",
                        new BigDecimal("4.7"),
                        List.of("Wasabi Mizuta", "Megumi Ohara", "Yumi Kakazu"),
                        List.of("hoat-hinh", "hai")),
                new MovieSeed(
                        "Shin Cậu Bé Bút Chì: Vương Quốc Nguệch Ngoạc",
                        "shin-cau-be-but-chi-vuong-quoc-nguech-ngoac",
                        "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?auto=format&fit=crop&w=500&q=80",
                        AgeRating.P,
                        MovieStatus.NOW_SHOWING,
                        Set.of(MovieFormat.TWO_D),
                        103,
                        LocalDate.of(2026, 5, 1),
                        "https://www.youtube.com/watch?v=4818B00K5tM",
                        "Shin cùng 4 dũng sĩ bất ổn quậy tung Vương quốc Nguệch Ngoạc để giải cứu Trái Đất bằng cây bút màu thần kỳ.",
                        new BigDecimal("4.4"),
                        List.of("Yumiko Kobayashi", "Miki Narahashi", "Toshiyuki Morikawa"),
                        List.of("hoat-hinh", "hai")));

        for (MovieSeed seed : seeds) {
            movieRepository.findBySlug(seed.slug()).orElseGet(() -> {
                Set<Genre> genres = new HashSet<>();
                for (String genreSlug : seed.genreSlugs()) {
                    Genre genre = genreRepository.findBySlug(genreSlug)
                            .orElseGet(() -> genreRepository
                                    .save(Genre.builder().name(genreSlug).slug(genreSlug).build()));
                    genres.add(genre);
                }

                Movie movie = Movie.builder()
                        .title(seed.title())
                        .slug(seed.slug())
                        .posterUrl(seed.posterUrl())
                        .ageRating(seed.ageRating())
                        .status(seed.status())
                        .formats(new HashSet<>(seed.formats()))
                        .duration(seed.duration())
                        .releaseDate(seed.releaseDate())
                        .trailerUrl(seed.trailerUrl())
                        .description(seed.description())
                        .ratingScore(seed.ratingScore())
                        .cast(seed.cast())
                        .genres(genres)
                        .build();

                return movieRepository.save(movie);
            });
        }
    }

    private record MovieSeed(
            String title,
            String slug,
            String posterUrl,
            AgeRating ageRating,
            MovieStatus status,
            Set<MovieFormat> formats,
            Integer duration,
            LocalDate releaseDate,
            String trailerUrl,
            String description,
            BigDecimal ratingScore,
            List<String> cast,
            List<String> genreSlugs) {
    }

    private void seedRooms() {
        List<RoomSeed> seeds = List.of(
                new RoomSeed("R1", "Phòng 1 (IMAX)",     6, 10, RoomType.IMAX),
                new RoomSeed("R2", "Phòng 2 (Standard)", 8, 12, RoomType.STANDARD));

        for (RoomSeed seed : seeds) {
            roomRepository.findByCode(seed.code()).orElseGet(() -> {
                Room room = Room.builder()
                        .code(seed.code())
                        .name(seed.name())
                        .totalRows(seed.totalRows())
                        .totalColumns(seed.totalColumns())
                        .roomType(seed.roomType())
                        .build(); // seats được khởi tạo bởi @Builder.Default trong entity

                generateSeats(room);
                return roomRepository.save(room);
            });
        }
    }

    private record RoomSeed(String code, String name, Integer totalRows, Integer totalColumns, RoomType roomType) {
    }

    private void generateSeats(Room room) {
        int totalRows = room.getTotalRows();
        if (totalRows > 26) {
            throw new IllegalArgumentException("totalRows must be <= 26");
        }

        for (int row = 1; row <= room.getTotalRows(); row++) {
            char rowChar = (char) ('A' + (row - 1));
            for (int col = 1; col <= room.getTotalColumns(); col++) {
                Seat seat = Seat.builder()
                        .room(room)
                        .label(rowChar + String.valueOf(col))
                        .rowNumber(row)
                        .columnNumber(col)
                        .type(SeatType.STANDARD)
                        .active(true)
                        .build();
                room.getSeats().add(seat);
            }
        }
    }

    private void seedShowtimes() {
        Room room1 = roomRepository.findByCode("R1").orElse(null);
        Room room2 = roomRepository.findByCode("R2").orElse(null);

        Movie mk2 = movieRepository.findBySlug("mortal-kombat-2-cuoc-chien-sinh-tu").orElse(null);
        Movie mandalorian = movieRepository.findBySlug("star-wars-the-mandalorian-and-grogu").orElse(null);
        Movie doraemon = movieRepository.findBySlug("doraemon-movie-45-nobita-va-lau-dai-duoi-day-bien").orElse(null);

        if (room1 == null || room2 == null || mk2 == null || mandalorian == null || doraemon == null) {
            return;
        }

        // Đặt lịch chiếu thực tế vào các khung giờ ngày 25/05/2026 sắp tới
        List<ShowtimeSeed> seeds = List.of(
                new ShowtimeSeed(mk2, room1, Instant.parse("2026-05-25T02:00:00Z"),
                        Instant.parse("2026-05-25T03:56:00Z"), new BigDecimal("140000")),
                new ShowtimeSeed(mandalorian, room1, Instant.parse("2026-05-25T05:00:00Z"),
                        Instant.parse("2026-05-25T07:05:00Z"), new BigDecimal("160000")),
                new ShowtimeSeed(doraemon, room2, Instant.parse("2026-05-25T03:30:00Z"),
                        Instant.parse("2026-05-25T05:18:00Z"), new BigDecimal("95000")));

        for (ShowtimeSeed seed : seeds) {
            showtimeRepository.findByRoomIdAndStartTime(seed.room().getId(), seed.startTime())
                    .orElseGet(() -> showtimeRepository.save(Showtime.builder()
                            .movie(seed.movie())
                            .room(seed.room())
                            .startTime(seed.startTime())
                            .endTime(seed.endTime())
                            .basePrice(seed.basePrice())
                            .build()));
        }
    }

    private record ShowtimeSeed(Movie movie, Room room, Instant startTime, Instant endTime, BigDecimal basePrice) {
    }
}