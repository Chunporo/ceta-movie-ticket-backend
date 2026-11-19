-- The director gets a profile photo like the cast do, and the literal text "None" that an early import stored becomes NULL.
ALTER TABLE movies ADD COLUMN director_photo_url VARCHAR(500) NULL;
UPDATE movies SET director = NULL WHERE director IN ('None', 'null', '');
