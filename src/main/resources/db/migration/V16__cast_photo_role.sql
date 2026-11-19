-- Cast members get a profile photo and the character they play (both optional; old rows keep just the name).
ALTER TABLE movie_cast
  ADD COLUMN profile_url VARCHAR(500) NULL,
  ADD COLUMN character_name VARCHAR(200) NULL;
