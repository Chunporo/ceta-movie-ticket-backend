-- D-22: an admin can cancel a showtime (mandatory reason); its PAID bookings are refunded.
ALTER TABLE showtime
  ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  ADD COLUMN cancelled_at DATETIME(6) NULL,
  ADD COLUMN cancel_reason VARCHAR(500) NULL;

ALTER TABLE refund_queue
  MODIFY COLUMN reason ENUM('LATE_PAYMENT','DOUBLE_PAY','HOLD_EXPIRED_DURING_PAY','SHOWTIME_CANCELLED') NOT NULL;
