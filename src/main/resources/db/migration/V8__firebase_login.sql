-- Firebase Authentication identity: the backend stays the source of truth for roles and bookings, Firebase only proves who the caller is.
ALTER TABLE users
  ADD COLUMN firebase_uid VARCHAR(128) NULL,
  ADD UNIQUE KEY uk_users_firebase_uid (firebase_uid);
