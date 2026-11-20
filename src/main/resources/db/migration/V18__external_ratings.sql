-- Scores of the same film on other sites (TMDB, IMDb, Rotten Tomatoes, Metacritic). They are shown beside, never mixed into,
-- the score of CETA customers who watched it.
ALTER TABLE movies
    ADD COLUMN imdb_id VARCHAR(20) NULL,
    ADD COLUMN imdb_rating DECIMAL(3,1) NULL,
    ADD COLUMN imdb_votes BIGINT NULL,
    ADD COLUMN rotten_tomatoes_pct INT NULL,
    ADD COLUMN metascore INT NULL,
    ADD COLUMN tmdb_rating DECIMAL(3,1) NULL,
    ADD COLUMN tmdb_votes BIGINT NULL,
    ADD COLUMN external_ratings_at TIMESTAMP NULL;
