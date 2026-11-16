-- Legacy tables exported from Hibernate using MySQLDialect; affected mappings follow BASELINE.
create table genres (created_at datetime(6) not null, id bigint not null auto_increment, updated_at datetime(6) not null, name varchar(100) not null, slug varchar(150) not null, primary key (id)) engine=InnoDB;
create table movie_cast (cast_order integer not null, movie_id binary(16) not null, cast_name varchar(200) not null, primary key (cast_order, movie_id)) engine=InnoDB;
create table movie_formats (movie_id binary(16) not null, format enum ('IMAX','THREE_D','TWO_D') not null, primary key (movie_id, format)) engine=InnoDB;
create table movie_genres (genre_id bigint not null, movie_id binary(16) not null, constraint uk_movie_genres_movie_genre primary key (movie_id, genre_id)) engine=InnoDB;
create table movies (duration integer not null, rating_score decimal(2,1), release_date date, created_at datetime(6) not null, updated_at datetime(6) not null, id binary(16) not null, slug varchar(150) not null, poster_url varchar(1000) not null, trailer_url varchar(1000), description TEXT, title varchar(255) not null, age_rating enum ('K','P','T13','T16','T18') not null, status enum ('COMING_SOON','EARLY_ACCESS','NOW_SHOWING') not null, primary key (id)) engine=InnoDB;
create table pricing_policies (active bit not null, multiplier decimal(10,2), surcharge decimal(10,2), id bigint not null auto_increment, day_of_week varchar(255), room_type enum ('COUPLE','FOUR_DX','GOLD_CLASS','IMAX','PREMIUM','SCREEN_X','STANDARD'), seat_type enum ('DELUXE','NORMAL','STANDARD','SWEETBOX','VIP','WHEELCHAIR'), primary key (id)) engine=InnoDB;
create table refresh_tokens (created_at datetime(6) not null, expires_at datetime(6) not null, id bigint not null auto_increment, revoked_at datetime(6), updated_at datetime(6) not null, user_id bigint not null, token varchar(256) not null, primary key (id)) engine=InnoDB;
create table roles (created_at datetime(6) not null, id bigint not null auto_increment, updated_at datetime(6) not null, name enum ('ADMIN','USER') not null, primary key (id)) engine=InnoDB;
create table rooms (total_columns integer not null, total_rows integer not null, created_at datetime(6) not null, id bigint not null auto_increment, updated_at datetime(6) not null, code varchar(20) not null, name varchar(100) not null, room_type enum ('COUPLE','FOUR_DX','GOLD_CLASS','IMAX','PREMIUM','SCREEN_X','STANDARD') not null, primary key (id)) engine=InnoDB;
create table seat (active bit not null, seat_col integer not null, seat_row integer not null, created_at datetime(6) not null, id bigint not null auto_increment, room_id bigint not null, updated_at datetime(6) not null, label varchar(10) not null, type enum ('DELUXE','NORMAL','STANDARD','SWEETBOX','VIP','WHEELCHAIR') not null, primary key (id)) engine=InnoDB;
create table showtime (base_price decimal(12,2) not null, created_at datetime(6) not null, end_time datetime(6) not null, id bigint not null auto_increment, room_id bigint not null, start_time datetime(6) not null, updated_at datetime(6) not null, movie_id binary(16) not null, primary key (id)) engine=InnoDB;
create table tickets (price decimal(12,2) not null, booking_id bigint not null, created_at datetime(6) not null, id bigint not null auto_increment, seat_id bigint not null, showtime_id bigint not null, updated_at datetime(6) not null, code varchar(64) not null, primary key (id)) engine=InnoDB;
create table user_roles (role_id bigint not null, user_id bigint not null, constraint uk_user_roles_user_role primary key (user_id, role_id)) engine=InnoDB;
create table users (enabled bit not null, created_at datetime(6) not null, id bigint not null auto_increment, updated_at datetime(6) not null, username varchar(50) not null, email varchar(320) not null, password_hash varchar(255) not null, primary key (id)) engine=InnoDB;

-- Baseline section 4, copied verbatim.
-- ============ Tồn kho ghế ============
CREATE TABLE showtime_seat (
  showtime_id BIGINT NOT NULL,
  seat_id     BIGINT NOT NULL,
  status      ENUM('AVAILABLE','HELD','SOLD','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  held_until  DATETIME NULL,
  booking_id  BIGINT NULL,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (showtime_id, seat_id),
  KEY idx_hold (status, held_until),
  KEY idx_hold_booking (booking_id),
  CONSTRAINT fk_ss_showtime FOREIGN KEY (showtime_id) REFERENCES showtime(id),
  CONSTRAINT fk_ss_seat     FOREIGN KEY (seat_id)     REFERENCES seat(id),
  CONSTRAINT ck_held CHECK (
    (status = 'HELD' AND held_until IS NOT NULL AND booking_id IS NOT NULL)
    OR (status <> 'HELD' AND held_until IS NULL AND booking_id IS NULL)
  )
) ENGINE=InnoDB;

-- ============ Booking ============
CREATE TABLE booking (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL,
  showtime_id   BIGINT NOT NULL,
  status        ENUM('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED') NOT NULL,
  total_amount  BIGINT NOT NULL,           -- chốt tại F2 (D-12)
  hold_expires_at DATETIME NOT NULL,       -- = held_until của ghế
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at       DATETIME NULL,
  active_key    VARCHAR(80) GENERATED ALWAYS AS (
                  CASE WHEN status = 'PENDING'
                       THEN CONCAT(user_id, ':', showtime_id) ELSE NULL END
                ) STORED,
  UNIQUE KEY uk_one_pending (active_key),  -- D-02
  KEY idx_expiry (status, hold_expires_at)
) ENGINE=InnoDB;

-- ============ Thanh toán ============
CREATE TABLE payment_transaction (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id   BIGINT NOT NULL,
  provider     VARCHAR(20) NOT NULL,       -- VNPAY
  txn_ref      VARCHAR(64) NOT NULL,       -- bookingId_attemptNo
  provider_txn VARCHAR(64) NULL,           -- vnp_TransactionNo
  amount       BIGINT NOT NULL,
  status       VARCHAR(20) NOT NULL,
  raw_payload  JSON NULL,
  processed_at DATETIME NULL,              -- cờ idempotency (D-09)
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider_txn (provider, txn_ref)
) ENGINE=InnoDB;

-- ============ Hoàn tiền (chỉ ca hiếm) ============
CREATE TABLE refund_queue (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id  BIGINT NOT NULL,
  reason      ENUM('LATE_PAYMENT','DOUBLE_PAY','HOLD_EXPIRED_DURING_PAY') NOT NULL,
  amount      BIGINT NOT NULL,
  status      ENUM('PENDING','DONE','FAILED') NOT NULL DEFAULT 'PENDING',
  note        VARCHAR(500) NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  done_at     DATETIME NULL,
  UNIQUE KEY uk_refund_booking (booking_id)   -- không hoàn 2 lần
) ENGINE=InnoDB;

-- ============ Audit ============
CREATE TABLE booking_status_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id BIGINT NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status   VARCHAR(20) NOT NULL,
  reason      VARCHAR(100) NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_bsh_booking (booking_id)
) ENGINE=InnoDB;

-- Existing indexes and foreign keys (ticket uniqueness retained).
create index idx_genres_slug on genres (slug);
alter table genres add constraint uk_genres_slug unique (slug);
alter table genres add constraint uk_genres_name unique (name);
create index idx_movies_slug on movies (slug);
create index idx_movies_status on movies (status);
create index idx_movies_release_date on movies (release_date);
alter table movies add constraint uk_movies_slug unique (slug);
create index idx_refresh_tokens_user_id on refresh_tokens (user_id);
alter table refresh_tokens add constraint uk_refresh_tokens_token unique (token);
alter table roles add constraint uk_roles_name unique (name);
alter table rooms add constraint uk_rooms_code unique (code);
create index idx_seats_room on seat (room_id);
alter table seat add constraint uk_seats_room_label unique (room_id, label);
create index idx_showtimes_movie on showtime (movie_id);
create index idx_showtimes_room on showtime (room_id);
create index idx_showtimes_start_time on showtime (start_time);
create index idx_tickets_booking on tickets (booking_id);
create index idx_tickets_showtime on tickets (showtime_id);
create index idx_tickets_seat on tickets (seat_id);
alter table tickets add constraint uk_tickets_showtime_seat unique (showtime_id, seat_id);
alter table users add constraint uk_users_username unique (username);
alter table users add constraint uk_users_email unique (email);
alter table movie_cast add constraint FKh3ht4nyhscwpt25ikwdu7lfqj foreign key (movie_id) references movies (id);
alter table movie_formats add constraint FK6ogd2ycfpfa0905gvxj3v71wr foreign key (movie_id) references movies (id);
alter table movie_genres add constraint FKnfpjuak0xiqpca3gjkwrixiig foreign key (genre_id) references genres (id);
alter table movie_genres add constraint FK4ak9svw913jblkfgru84h2phd foreign key (movie_id) references movies (id);
alter table refresh_tokens add constraint FK1lih5y2npsf8u5o3vhdb9y0os foreign key (user_id) references users (id);
alter table seat add constraint FKg993pi7ucgy616icmddq8u335 foreign key (room_id) references rooms (id);
alter table showtime add constraint FKeltpyuei1d5g3n6ikpsjwwil6 foreign key (movie_id) references movies (id);
alter table showtime add constraint FKrumrrbei9jppryk4teoyoetit foreign key (room_id) references rooms (id);
alter table tickets add constraint FKefja4avuu7g29t78mxifrsynb foreign key (booking_id) references booking (id);
alter table tickets add constraint FK1f6n3pv4b80wl6gj4ra32ctxk foreign key (seat_id) references seat (id);
alter table tickets add constraint FKo0u22315eoxdv59tn6wsdn8b1 foreign key (showtime_id) references showtime (id);
alter table user_roles add constraint FKh8ciramu9cc9q3qcqiv4ue8a6 foreign key (role_id) references roles (id);
alter table user_roles add constraint FKhfh9dx7w3ubf1co1vdev94g3f foreign key (user_id) references users (id);

-- One cinema is implicit in this single-site deployment. Seed one room and tomorrow's showtime in UTC.
INSERT INTO rooms (id,code,name,total_rows,total_columns,room_type,created_at,updated_at) VALUES (1,'CETA-01','CETA Room 1',8,15,'STANDARD',UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO movies (id,title,slug,poster_url,age_rating,status,duration,release_date,created_at,updated_at) VALUES (UNHEX('00000000000000000000000000000001'),'CETA Demo','ceta-demo','https://example.invalid/poster.png','P','NOW_SHOWING',120,UTC_DATE(),UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO movie_formats (movie_id,format) VALUES (UNHEX('00000000000000000000000000000001'),'TWO_D');
INSERT INTO seat (id,room_id,label,seat_row,seat_col,type,active,created_at,updated_at) VALUES
(1,1,'A1',1,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(2,1,'A2',1,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(3,1,'A3',1,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(4,1,'A4',1,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(5,1,'A5',1,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(6,1,'A6',1,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(7,1,'A7',1,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(8,1,'A8',1,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(9,1,'A9',1,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(10,1,'A10',1,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(11,1,'A11',1,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(12,1,'A12',1,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(13,1,'A13',1,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(14,1,'A14',1,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(15,1,'A15',1,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(16,1,'B1',2,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(17,1,'B2',2,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(18,1,'B3',2,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(19,1,'B4',2,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(20,1,'B5',2,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(21,1,'B6',2,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(22,1,'B7',2,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(23,1,'B8',2,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(24,1,'B9',2,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(25,1,'B10',2,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(26,1,'B11',2,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(27,1,'B12',2,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(28,1,'B13',2,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(29,1,'B14',2,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(30,1,'B15',2,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(31,1,'C1',3,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(32,1,'C2',3,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(33,1,'C3',3,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(34,1,'C4',3,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(35,1,'C5',3,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(36,1,'C6',3,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(37,1,'C7',3,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(38,1,'C8',3,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(39,1,'C9',3,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(40,1,'C10',3,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(41,1,'C11',3,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(42,1,'C12',3,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(43,1,'C13',3,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(44,1,'C14',3,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(45,1,'C15',3,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(46,1,'D1',4,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(47,1,'D2',4,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(48,1,'D3',4,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(49,1,'D4',4,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(50,1,'D5',4,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(51,1,'D6',4,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(52,1,'D7',4,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(53,1,'D8',4,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(54,1,'D9',4,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(55,1,'D10',4,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(56,1,'D11',4,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(57,1,'D12',4,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(58,1,'D13',4,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(59,1,'D14',4,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(60,1,'D15',4,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(61,1,'E1',5,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(62,1,'E2',5,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(63,1,'E3',5,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(64,1,'E4',5,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(65,1,'E5',5,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(66,1,'E6',5,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(67,1,'E7',5,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(68,1,'E8',5,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(69,1,'E9',5,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(70,1,'E10',5,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(71,1,'E11',5,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(72,1,'E12',5,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(73,1,'E13',5,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(74,1,'E14',5,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(75,1,'E15',5,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(76,1,'F1',6,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(77,1,'F2',6,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(78,1,'F3',6,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(79,1,'F4',6,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(80,1,'F5',6,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(81,1,'F6',6,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(82,1,'F7',6,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(83,1,'F8',6,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(84,1,'F9',6,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(85,1,'F10',6,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(86,1,'F11',6,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(87,1,'F12',6,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(88,1,'F13',6,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(89,1,'F14',6,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(90,1,'F15',6,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(91,1,'G1',7,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(92,1,'G2',7,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(93,1,'G3',7,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(94,1,'G4',7,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(95,1,'G5',7,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(96,1,'G6',7,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(97,1,'G7',7,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(98,1,'G8',7,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(99,1,'G9',7,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(100,1,'G10',7,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(101,1,'G11',7,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(102,1,'G12',7,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(103,1,'G13',7,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(104,1,'G14',7,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(105,1,'G15',7,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(106,1,'H1',8,1,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(107,1,'H2',8,2,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(108,1,'H3',8,3,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(109,1,'H4',8,4,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(110,1,'H5',8,5,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(111,1,'H6',8,6,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(112,1,'H7',8,7,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(113,1,'H8',8,8,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(114,1,'H9',8,9,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(115,1,'H10',8,10,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(116,1,'H11',8,11,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(117,1,'H12',8,12,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(118,1,'H13',8,13,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(119,1,'H14',8,14,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),
(120,1,'H15',8,15,'STANDARD',1,UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO showtime (id,movie_id,room_id,start_time,end_time,base_price,created_at,updated_at) VALUES (1,UNHEX('00000000000000000000000000000001'),1,UTC_DATE() + INTERVAL 1 DAY + INTERVAL 12 HOUR,UTC_DATE() + INTERVAL 1 DAY + INTERVAL 14 HOUR,100000,UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO showtime_seat (showtime_id,seat_id) SELECT 1,id FROM seat WHERE room_id=1;
