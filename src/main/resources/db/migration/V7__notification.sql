-- S14: in-app notifications (orders only in v1). No FK to users on purpose: a notification is a log line,
-- and the unique key makes every producer idempotent (one notification per user, type and booking).
CREATE TABLE notification (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  type        VARCHAR(30) NOT NULL,
  title       VARCHAR(200) NOT NULL,
  body        VARCHAR(500) NOT NULL,
  booking_id  BIGINT NULL,
  created_at  DATETIME(6) NOT NULL,
  read_at     DATETIME(6) NULL,
  UNIQUE KEY uk_notification_once (user_id, type, booking_id),
  KEY idx_notification_user (user_id, id)
) ENGINE=InnoDB;
