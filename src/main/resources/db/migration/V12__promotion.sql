-- B4: offers and vouchers. A voucher is applied to a PENDING booking before payment starts; the discount is frozen with the total.
CREATE TABLE promotion (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  code             VARCHAR(30)  NOT NULL,
  title            VARCHAR(100) NOT NULL,
  title_en         VARCHAR(100) NULL,
  description      VARCHAR(255) NULL,
  description_en   VARCHAR(255) NULL,
  image_url        VARCHAR(500) NULL,
  kind             VARCHAR(24)  NOT NULL,   -- PERCENT_OFF | FIXED_TICKET_PRICE | COUNTER_PERK (shown, not applied online)
  percent_off      INT NULL,
  max_discount     BIGINT NULL,
  fixed_price      BIGINT NULL,
  weekdays         VARCHAR(80) NULL,        -- e.g. TUESDAY or SATURDAY,SUNDAY (cinema time zone)
  formats          VARCHAR(40) NULL,        -- e.g. TWO_D or IMAX,THREE_D
  first_order_only BIT NOT NULL DEFAULT 0,
  once_per_user    BIT NOT NULL DEFAULT 0,
  valid_from       DATETIME(6) NULL,
  valid_until      DATETIME(6) NULL,
  active           BIT NOT NULL DEFAULT 1,
  sort_order       INT NOT NULL DEFAULT 0,
  created_at       DATETIME(6) NOT NULL,
  updated_at       DATETIME(6) NOT NULL,
  UNIQUE KEY uk_promotion_code (code)
) ENGINE=InnoDB;

-- The customer's voucher wallet ("Luu ma").
CREATE TABLE user_voucher (
  user_id      BIGINT NOT NULL,
  promotion_id BIGINT NOT NULL,
  saved_at     DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id, promotion_id),
  CONSTRAINT fk_uvoucher_user      FOREIGN KEY (user_id)      REFERENCES users(id),
  CONSTRAINT fk_uvoucher_promotion FOREIGN KEY (promotion_id) REFERENCES promotion(id)
) ENGINE=InnoDB;

-- The voucher a booking uses, with the discount as frozen. A voucher counts as used while its booking is PENDING or PAID.
CREATE TABLE booking_promotion (
  booking_id   BIGINT PRIMARY KEY,
  promotion_id BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  code         VARCHAR(30) NOT NULL,
  discount     BIGINT NOT NULL,
  created_at   DATETIME(6) NOT NULL,
  KEY idx_bpromo_user_promo (user_id, promotion_id),
  CONSTRAINT fk_bpromo_booking   FOREIGN KEY (booking_id)   REFERENCES booking(id),
  CONSTRAINT fk_bpromo_promotion FOREIGN KEY (promotion_id) REFERENCES promotion(id)
) ENGINE=InnoDB;

ALTER TABLE booking ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0;
