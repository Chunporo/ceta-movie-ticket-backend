-- B1: a cinema (rạp) groups rooms and belongs to a city. Existing rooms move to one default cinema.
CREATE TABLE cinema (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(20)  NOT NULL,
  name        VARCHAR(100) NOT NULL,
  city        VARCHAR(20)  NOT NULL,
  address     VARCHAR(255) NOT NULL,
  latitude    DECIMAL(9,6) NULL,
  longitude   DECIMAL(9,6) NULL,
  active      BIT NOT NULL DEFAULT 1,
  created_at  DATETIME(6) NOT NULL,
  updated_at  DATETIME(6) NOT NULL,
  UNIQUE KEY uk_cinema_code (code),
  KEY idx_cinema_city (city)
) ENGINE=InnoDB;

INSERT INTO cinema (code, name, city, address, active, created_at, updated_at)
VALUES ('MAIN', 'CETA Cinema', 'HCM', 'TP. Hồ Chí Minh', 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

ALTER TABLE rooms ADD COLUMN cinema_id BIGINT NULL;
UPDATE rooms SET cinema_id = (SELECT id FROM cinema WHERE code = 'MAIN');
ALTER TABLE rooms MODIFY COLUMN cinema_id BIGINT NOT NULL;
ALTER TABLE rooms ADD CONSTRAINT fk_rooms_cinema FOREIGN KEY (cinema_id) REFERENCES cinema(id);
CREATE INDEX idx_rooms_cinema ON rooms (cinema_id);
