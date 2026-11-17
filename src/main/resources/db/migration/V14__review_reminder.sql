-- B6: movie reviews (1..10, half-star steps of a 5-star display) and "remind me when tickets go on sale".
CREATE TABLE movie_review (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  movie_id   BINARY(16) NOT NULL,
  user_id    BIGINT NOT NULL,
  rating     INT NOT NULL,
  comment    VARCHAR(1000) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_review_movie_user (movie_id, user_id),
  KEY idx_review_movie (movie_id, id),
  KEY idx_review_user (user_id),
  CONSTRAINT fk_review_movie FOREIGN KEY (movie_id) REFERENCES movies(id),
  CONSTRAINT fk_review_user  FOREIGN KEY (user_id)  REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE movie_reminder (
  user_id    BIGINT NOT NULL,
  movie_id   BINARY(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id, movie_id),
  KEY idx_reminder_movie (movie_id),
  CONSTRAINT fk_reminder_user  FOREIGN KEY (user_id)  REFERENCES users(id),
  CONSTRAINT fk_reminder_movie FOREIGN KEY (movie_id) REFERENCES movies(id)
) ENGINE=InnoDB;

ALTER TABLE notification ADD COLUMN movie_id BINARY(16) NULL;
