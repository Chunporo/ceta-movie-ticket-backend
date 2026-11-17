-- B3: snack combos. booking.total_amount = seats + combo_amount (- voucher - points, added in B4/B5), frozen at creation.
CREATE TABLE combo (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  code           VARCHAR(30)  NOT NULL,
  name           VARCHAR(100) NOT NULL,
  name_en        VARCHAR(100) NULL,
  description    VARCHAR(255) NULL,
  description_en VARCHAR(255) NULL,
  image_url      VARCHAR(500) NULL,
  price          BIGINT NOT NULL,
  active         BIT NOT NULL DEFAULT 1,
  sort_order     INT NOT NULL DEFAULT 0,
  created_at     DATETIME(6) NOT NULL,
  updated_at     DATETIME(6) NOT NULL,
  UNIQUE KEY uk_combo_code (code)
) ENGINE=InnoDB;

-- What was bought, at the price and name of that moment (a later rename or price change never touches it).
CREATE TABLE booking_combo (
  booking_id BIGINT NOT NULL,
  combo_id   BIGINT NOT NULL,
  name       VARCHAR(100) NOT NULL,
  quantity   INT NOT NULL,
  unit_price BIGINT NOT NULL,
  PRIMARY KEY (booking_id, combo_id),
  CONSTRAINT fk_bcombo_booking FOREIGN KEY (booking_id) REFERENCES booking(id),
  CONSTRAINT fk_bcombo_combo   FOREIGN KEY (combo_id)   REFERENCES combo(id)
) ENGINE=InnoDB;

ALTER TABLE booking ADD COLUMN combo_amount BIGINT NOT NULL DEFAULT 0;
