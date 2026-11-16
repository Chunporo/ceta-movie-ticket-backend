-- T4: per-seat price snapshot for a booking (BASELINE D-24, D-12). Minimal by design:
-- sum(price) must equal booking.total_amount, asserted in application code.
CREATE TABLE booking_seat (
  booking_id BIGINT NOT NULL,
  seat_id    BIGINT NOT NULL,
  price      BIGINT NOT NULL,
  PRIMARY KEY (booking_id, seat_id),
  CONSTRAINT fk_bseat_booking FOREIGN KEY (booking_id) REFERENCES booking(id),
  CONSTRAINT fk_bseat_seat    FOREIGN KEY (seat_id)    REFERENCES seat(id)
) ENGINE=InnoDB;
