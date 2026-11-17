-- B8: sign in with a phone number (Firebase phone auth). Such a customer has no email, so users.email holds an internal
-- placeholder (see User.PHONE_EMAIL_DOMAIN) that no mail is ever sent to; the real number lives here.
ALTER TABLE users
  ADD COLUMN phone_number VARCHAR(20) NULL,
  ADD UNIQUE KEY uk_users_phone (phone_number);
