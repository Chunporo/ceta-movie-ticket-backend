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
    private final com.movie.ticket_booking.repository.CinemaRepository cinemaRepository;
    private final com.movie.ticket_booking.repository.ComboRepository comboRepository;
    private final com.movie.ticket_booking.repository.PromotionRepository promotionRepository;
    private final ShowtimeRepository showtimeRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.movie.ticket_booking.service.inventory.InventoryService inventory;

    public DataSeeder(
            GenreRepository genreRepository,
            MovieRepository movieRepository,
            RoomRepository roomRepository,
            com.movie.ticket_booking.repository.CinemaRepository cinemaRepository,
            com.movie.ticket_booking.repository.ComboRepository comboRepository,
            com.movie.ticket_booking.repository.PromotionRepository promotionRepository,
            ShowtimeRepository showtimeRepository,
            RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            com.movie.ticket_booking.service.inventory.InventoryService inventory) {
        this.genreRepository = genreRepository;
        this.movieRepository = movieRepository;
        this.roomRepository = roomRepository;
        this.cinemaRepository = cinemaRepository;
        this.comboRepository = comboRepository;
        this.promotionRepository = promotionRepository;
        this.showtimeRepository = showtimeRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.inventory = inventory;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedRoles();
        seedAdminUser();
        seedGenres();
        seedMovies();
        seedCinemas();
        seedCombos();
        seedPromotions();
        seedRooms();
        seedShowtimes();
        seedUpcomingShowtimes();
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
                new GenreSeed("Võ thuật", "vo-thuat"),
                new GenreSeed("Tình cảm", "tinh-cam"));

        for (GenreSeed seed : seeds) {
            genreRepository.findBySlug(seed.slug())
                    .orElseGet(() -> genreRepository.save(Genre.builder().name(seed.name()).slug(seed.slug()).build()));
        }
    }

    private record GenreSeed(String name, String slug) {
    }

    private void seedMovies() {
        // No made-up movies: the catalogue is real data loaded with scripts/import_tmdb.py (TMDB).
        List<MovieSeed> seeds = List.of();

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
                        .cast(seed.cast().stream().map(com.movie.ticket_booking.entity.CastMember::named).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)))
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

    private void seedCombos() {
        record ComboSeed(String code, String name, String nameEn, String description, String descriptionEn, long price) {}
        List<ComboSeed> seeds = List.of(
                new ComboSeed("SOLO", "Combo Solo", "Solo Combo", "1 bắp ngọt (M) + 1 nước ngọt (L)", "1 sweet popcorn (M) + 1 soft drink (L)", 79_000),
                new ComboSeed("COUPLE", "Combo Đôi", "Couple Combo", "1 bắp phô mai (L) + 2 nước ngọt (L)", "1 cheese popcorn (L) + 2 soft drinks (L)", 119_000),
                new ComboSeed("FAMILY", "Combo Gia Đình", "Family Combo", "2 bắp (L) + 4 nước ngọt + 1 snack", "2 popcorn (L) + 4 soft drinks + 1 snack", 229_000),
                new ComboSeed("CARAMEL", "Bắp caramel (L)", "Caramel popcorn (L)", "Bắp rang caramel giòn, size lớn", "Crunchy caramel popcorn, large", 59_000),
                new ComboSeed("DRINK", "Nước ngọt (L)", "Soft drink (L)", "Chọn vị tại quầy", "Choose flavor at the counter", 35_000));
        int order = 0;
        for (ComboSeed seed : seeds) {
            int sortOrder = order++;
            comboRepository.findByCode(seed.code()).orElseGet(() -> comboRepository.save(com.movie.ticket_booking.entity.Combo.builder()
                    .code(seed.code()).name(seed.name()).nameEn(seed.nameEn()).description(seed.description())
                    .descriptionEn(seed.descriptionEn()).price(seed.price()).sortOrder(sortOrder).build()));
        }
    }

    private void seedPromotions() {
        // End dates are the end of that day in Vietnam (UTC+7).
        promotionRepository.findByCode("TUE50").orElseGet(() -> promotionRepository.save(com.movie.ticket_booking.entity.Promotion.builder()
                .code("TUE50").title("Thứ Ba Vui Vẻ").titleEn("Happy Tuesday")
                .description("Đồng giá 50.000đ cho mọi suất 2D vào thứ Ba hằng tuần.").descriptionEn("All 2D shows at 50,000đ every Tuesday.")
                .kind(com.movie.ticket_booking.entity.enums.PromotionKind.FIXED_TICKET_PRICE).fixedPrice(50_000L)
                .weekdays("TUESDAY").formats("TWO_D").validUntil(Instant.parse("2026-12-31T16:59:59Z")).sortOrder(0).build()));
        promotionRepository.findByCode("CETA20").orElseGet(() -> promotionRepository.save(com.movie.ticket_booking.entity.Promotion.builder()
                .code("CETA20").title("Thành viên mới -20%").titleEn("New members -20%")
                .description("Giảm 20% tiền vé cho đơn đầu tiên, tối đa 50.000đ.").descriptionEn("20% off tickets on your first order, up to 50,000đ.")
                .kind(com.movie.ticket_booking.entity.enums.PromotionKind.PERCENT_OFF).percentOff(20).maxDiscount(50_000L)
                .firstOrderOnly(true).oncePerUser(true).validUntil(Instant.parse("2027-01-31T16:59:59Z")).sortOrder(1).build()));
        promotionRepository.findByCode("HSSV49").orElseGet(() -> promotionRepository.save(com.movie.ticket_booking.entity.Promotion.builder()
                .code("HSSV49").title("Combo sinh viên").titleEn("Student combo")
                .description("Bắp + nước chỉ 49.000đ khi xuất trình thẻ sinh viên tại quầy.").descriptionEn("Popcorn + drink for 49,000đ with a student ID at the counter.")
                .kind(com.movie.ticket_booking.entity.enums.PromotionKind.COUNTER_PERK).validUntil(Instant.parse("2026-12-15T16:59:59Z")).sortOrder(2).build()));
        promotionRepository.findByCode("IMAXWKND").orElseGet(() -> promotionRepository.save(com.movie.ticket_booking.entity.Promotion.builder()
                .code("IMAXWKND").title("IMAX cuối tuần").titleEn("IMAX weekends")
                .description("Tặng 1 nước ngọt khi mua 2 vé IMAX vào thứ Bảy, Chủ nhật.").descriptionEn("Free soft drink with 2 IMAX tickets on Saturdays and Sundays.")
                .kind(com.movie.ticket_booking.entity.enums.PromotionKind.COUNTER_PERK).weekdays("SATURDAY,SUNDAY").formats("IMAX")
                .validUntil(Instant.parse("2027-02-28T16:59:59Z")).sortOrder(3).build()));
    }

    private record CinemaSeed(String code, String name, String city, String address, String lat, String lng, boolean imax) {
    }

    private void seedCinemas() {
        List<CinemaSeed> seeds = List.of(
                new CinemaSeed("LANDMARK", "CETA Landmark", "HCM", "208 Nguyễn Hữu Cảnh, Bình Thạnh", "10.794700", "106.721900", true),
                new CinemaSeed("NGUYENDU", "CETA Nguyễn Du", "HCM", "116 Nguyễn Du, Quận 1", "10.775800", "106.695500", false),
                new CinemaSeed("THAODIEN", "CETA Thảo Điền", "HCM", "12 Quốc Hương, Thủ Đức", "10.803300", "106.733100", false),
                new CinemaSeed("BATRIEU", "CETA Bà Triệu", "HN", "191 Bà Triệu, Hai Bà Trưng", "21.012700", "105.849800", true),
                new CinemaSeed("TAYHO", "CETA Tây Hồ", "HN", "36 Xuân Diệu, Tây Hồ", "21.062600", "105.829900", false));
        for (CinemaSeed seed : seeds) {
            cinemaRepository.findByCode(seed.code()).orElseGet(() -> cinemaRepository.save(
                    com.movie.ticket_booking.entity.Cinema.builder()
                            .code(seed.code()).name(seed.name()).city(seed.city()).address(seed.address())
                            .latitude(new BigDecimal(seed.lat())).longitude(new BigDecimal(seed.lng()))
                            .build()));
        }
    }

    private void seedRooms() {
        com.movie.ticket_booking.entity.Cinema landmark = cinemaRepository.findByCode("LANDMARK").orElse(null);
        List<RoomSeed> seeds = new java.util.ArrayList<>(List.of(
                new RoomSeed("R1", "Phòng 1 (IMAX)",     6, 10, RoomType.IMAX, landmark),
                new RoomSeed("R2", "Phòng 2 (Standard)", 8, 12, RoomType.STANDARD, landmark)));
        // Every cinema gets two standard rooms (and an IMAX one where the cinema has IMAX), so a busy week of
        // showtimes fits without clashes. Landmark is listed here too: its R1/R2 may already belong to the first cinema.
        for (CinemaSeed c : List.of(
                new CinemaSeed("LANDMARK", null, null, null, null, null, false),
                new CinemaSeed("NGUYENDU", null, null, null, null, null, false),
                new CinemaSeed("THAODIEN", null, null, null, null, null, false),
                new CinemaSeed("BATRIEU", null, null, null, null, null, true),
                new CinemaSeed("TAYHO", null, null, null, null, null, false))) {
            com.movie.ticket_booking.entity.Cinema cinema = cinemaRepository.findByCode(c.code()).orElse(null);
            seeds.add(new RoomSeed(c.code() + "-1", "Phòng 1", 8, 12, RoomType.STANDARD, cinema));
            seeds.add(new RoomSeed(c.code() + "-2", "Phòng 2", 8, 12, RoomType.STANDARD, cinema));
            if (c.imax()) {
                seeds.add(new RoomSeed(c.code() + "-IMAX", "Phòng IMAX", 6, 10, RoomType.IMAX, cinema));
            }
        }

        for (RoomSeed seed : seeds) {
            roomRepository.findByCode(seed.code()).orElseGet(() -> {
                Room room = Room.builder()
                        .cinema(seed.cinema())
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

    private record RoomSeed(String code, String name, Integer totalRows, Integer totalColumns, RoomType roomType,
                            com.movie.ticket_booking.entity.Cinema cinema) {
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
                        .type(seatTypeFor(row, totalRows))
                        .active(true)
                        .build();
                room.getSeats().add(seat);
            }
        }
    }

    /** Demo layout like the app design: the last row is couple seats, the middle rows are VIP, the rest standard. */
    private static SeatType seatTypeFor(int row, int totalRows) {
        if (totalRows < 4) return SeatType.STANDARD;
        if (row == totalRows) return SeatType.SWEETBOX;
        return row >= totalRows / 2 && row < totalRows ? SeatType.VIP : SeatType.STANDARD;
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
            Showtime showtime = showtimeRepository.findByRoomIdAndStartTime(seed.room().getId(), seed.startTime())
                    .orElseGet(() -> showtimeRepository.save(Showtime.builder()
                            .movie(seed.movie())
                            .room(seed.room())
                            .startTime(seed.startTime())
                            .endTime(seed.endTime())
                            .basePrice(seed.basePrice())
                            .format(seed.room().getRoomType() == RoomType.IMAX
                                    ? com.movie.ticket_booking.entity.enums.MovieFormat.IMAX
                                    : com.movie.ticket_booking.entity.enums.MovieFormat.TWO_D)
                            .build()));
            inventory.initializeShowtime(showtime.getId());
        }
    }

    /**
     * A rolling week of showings so the app always has something to book: four slots a day in every room, rotating through
     * the movies that are on screens. Safe to run on every start: a showing that already exists or would overlap is skipped,
     * and a coming-soon movie never gets one (that is what its "remind me" is waiting for).
     */
    private void seedUpcomingShowtimes() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        List<Movie> onScreen = movieRepository.findAll().stream()
                .filter(m -> m.getStatus() == MovieStatus.NOW_SHOWING && m.getDuration() != null)
                .sorted(java.util.Comparator.comparing(Movie::getSlug)).toList();
        List<Room> rooms = roomRepository.findAll().stream().sorted(java.util.Comparator.comparing(Room::getCode)).toList();
        if (onScreen.isEmpty() || rooms.isEmpty()) {
            return;
        }
        java.time.LocalTime[] slots = {java.time.LocalTime.of(10, 0), java.time.LocalTime.of(13, 0),
                java.time.LocalTime.of(16, 0), java.time.LocalTime.of(19, 30)};
        LocalDate today = LocalDate.now(zone);
        Instant now = Instant.now();
        for (int day = 0; day < 7; day++) {
            for (int r = 0; r < rooms.size(); r++) {
                Room room = rooms.get(r);
                for (int i = 0; i < slots.length; i++) {
                    Movie movie = onScreen.get((r + i + day) % onScreen.size());
                    Instant start = today.plusDays(day).atTime(slots[i]).atZone(zone).toInstant();
                    if (!start.isAfter(now)) continue;
                    Instant end = start.plusSeconds((movie.getDuration() + 20L) * 60);
                    if (showtimeRepository.findByRoomIdAndStartTime(room.getId(), start).isPresent()
                            || showtimeRepository.existsOverlappingInRoom(room.getId(), start, end, null)) continue;
                    Showtime showtime = showtimeRepository.save(Showtime.builder().movie(movie).room(room)
                            .startTime(start).endTime(end).basePrice(new BigDecimal("85000"))
                            .format(room.getRoomType() == RoomType.IMAX
                                    ? com.movie.ticket_booking.entity.enums.MovieFormat.IMAX
                                    : com.movie.ticket_booking.entity.enums.MovieFormat.TWO_D)
                            .languageMode((day + i) % 3 == 0 ? com.movie.ticket_booking.entity.enums.LanguageMode.DUB
                                    : com.movie.ticket_booking.entity.enums.LanguageMode.SUB)
                            .build());
                    inventory.initializeShowtime(showtime.getId());
                }
            }
        }
    }

    private record ShowtimeSeed(Movie movie, Room room, Instant startTime, Instant endTime, BigDecimal basePrice) {
    }
}