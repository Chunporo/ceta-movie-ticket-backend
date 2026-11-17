-- B5: CETA points. The ledger is append-only; whether a row counts depends on its booking's status (see LoyaltyEntryRepository).
CREATE TABLE loyalty_entry (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  delta      INT NOT NULL,                 -- + earned, - spent
  reason     VARCHAR(16) NOT NULL,         -- EARN | REDEEM | ADJUSTMENT
  booking_id BIGINT NULL,
  note       VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_lentry_booking_reason (booking_id, reason),
  KEY idx_lentry_user (user_id),
  CONSTRAINT fk_lentry_user    FOREIGN KEY (user_id)    REFERENCES users(id),
  CONSTRAINT fk_lentry_booking FOREIGN KEY (booking_id) REFERENCES booking(id)
) ENGINE=InnoDB;

ALTER TABLE booking
  ADD COLUMN points_amount BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN points_used   INT    NOT NULL DEFAULT 0;
