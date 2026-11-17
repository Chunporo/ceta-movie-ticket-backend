-- US-01 login lockout, US-03 forgot password, US-17 account deletion (anonymised, bookings are kept).
ALTER TABLE users
  ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN locked_until DATETIME(6) NULL,
  ADD COLUMN deleted_at DATETIME(6) NULL;

CREATE TABLE password_reset_token (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  token_hash  CHAR(64) NOT NULL,
  expires_at  DATETIME(6) NOT NULL,
  used_at     DATETIME(6) NULL,
  created_at  DATETIME(6) NOT NULL,
  UNIQUE KEY uk_password_reset_hash (token_hash),
  KEY idx_password_reset_user (user_id),
  CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
