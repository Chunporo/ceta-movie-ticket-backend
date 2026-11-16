package com.movie.ticket_booking.service.inventory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL: the startup seeder and the init SQL leave every showtime with one inventory row per seat. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class InventoryCoverageMysqlTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryService inventory;

    @Test
    void everyShowtimeHasAnInventoryRowForEverySeatOfItsRoom() {
        List<Map<String, Object>> gaps = jdbc.queryForList("""
                SELECT sh.id AS showtime_id,
                       (SELECT COUNT(*) FROM seat s WHERE s.room_id = sh.room_id) AS seats,
                       (SELECT COUNT(*) FROM showtime_seat x WHERE x.showtime_id = sh.id) AS inventory
                FROM showtime sh
                HAVING seats <> inventory
                """);
        assertTrue(gaps.isEmpty(), "showtimes without full inventory: " + gaps);
    }

    @Test
    void initializingAgainIsAHarmlessNoOp() {
        Long showtimeId = jdbc.queryForObject("SELECT MIN(id) FROM showtime", Long.class);
        assertEquals(0, inventory.initializeShowtime(showtimeId));
    }
}
