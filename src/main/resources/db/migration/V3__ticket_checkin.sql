-- T10: staff check-in. A ticket is used once; the conditional UPDATE on checked_in_at IS NULL is the guard.
ALTER TABLE tickets
  ADD COLUMN checked_in_at DATETIME(6) NULL,
  ADD COLUMN checked_in_by BIGINT NULL;
