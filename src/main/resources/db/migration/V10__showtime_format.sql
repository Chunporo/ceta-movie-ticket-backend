-- B2: a showtime has a format (2D/3D/IMAX) and a language mode (subtitled/dubbed); a movie has a director and language.
ALTER TABLE showtime
  ADD COLUMN format VARCHAR(10) NOT NULL DEFAULT 'TWO_D',
  ADD COLUMN language_mode VARCHAR(10) NOT NULL DEFAULT 'SUB';

-- Showtimes in an IMAX room used to be priced by a room multiplier; they are IMAX showtimes now.
UPDATE showtime SET format = 'IMAX' WHERE room_id IN (SELECT id FROM rooms WHERE room_type = 'IMAX');

ALTER TABLE movies
  ADD COLUMN director VARCHAR(100) NULL,
  ADD COLUMN language VARCHAR(10) NULL;
