-- D-15: a customer may cancel a PAID booking until 60 minutes before the show; the refund is queued for an admin.
ALTER TABLE refund_queue
  MODIFY COLUMN reason ENUM('LATE_PAYMENT','DOUBLE_PAY','HOLD_EXPIRED_DURING_PAY','SHOWTIME_CANCELLED','CUSTOMER_CANCEL') NOT NULL;
