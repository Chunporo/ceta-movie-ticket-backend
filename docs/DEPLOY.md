# Deploy (free tier) and release

Backend = one Docker image (`Dockerfile`, profile `prod`). It needs a MySQL 8 database and nothing else (Redis is not used yet).
Suggested free stack: **Render** (web service, Docker) + **Aiven** (free MySQL). A free Render service sleeps after ~15 minutes without traffic and
takes about a minute to wake, which is fine for a portfolio demo (the booking sweeper pauses while it sleeps and catches up on wake).

## 1. Database
Create a free MySQL service (Aiven, or any MySQL 8) and note host, port, user, password and database name. Flyway builds the schema on first start.
JDBC URL: `jdbc:mysql://HOST:PORT/DB?sslMode=REQUIRED&serverTimezone=UTC`.

## 2. Web service (Render)
New > Blueprint > pick this repo (`render.yaml`), or New > Web Service > Docker. Environment variables:

| Variable | Value |
|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | from step 1 |
| `JWT_SECRET` | at least 32 random bytes (`openssl rand -hex 32`) |
| `PUBLIC_BASE_URL` | the service URL, for example `https://ceta-backend.onrender.com` (no trailing slash) |
| `ADMIN_PASSWORD`, `ADMIN_EMAIL` | the first admin login. Without `ADMIN_PASSWORD` no admin account is created |
| `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` | VNPay **sandbox** merchant values (optional; without them paying fails) |
| `SMTP_USERNAME`, `SMTP_PASSWORD`, profile `prod,dev` | only if you want confirmation emails (the `dev` profile carries the SMTP and Swagger settings) |

The seeder fills the demo catalogue (cinemas, rooms, combos, offers, showtimes) on first start. The well-known dev admin `admin@gmail.com / admin@123` is **not** created in `prod`.
Check: `GET $PUBLIC_BASE_URL/api/v1/movies` answers a JSON list.

## 3. Firebase
Sign-in needs no server credentials (the backend only checks ID tokens against project `ceta-dedff`). The release signing certificate (SHA-1 and SHA-256) is registered on the Android app `com.ceta.cinema`.

## 4. Android release (GitHub Releases)
Once, in the Android repo: secrets `CETA_KEYSTORE_B64` (`base64 -w0 ~/ceta-release.jks`), `CETA_KEYSTORE_PASSWORD`, `CETA_KEY_PASSWORD`, `CETA_KEY_ALIAS` (`ceta`), and the variable `API_BASE_URL` = `https://<your host>/api/v1/`.
Then `git tag v1.0.0 && git push origin v1.0.0` (or run the Release workflow by hand): it tests, builds the signed prod APK and publishes it, with a checksum, as a GitHub Release.
**Back up `~/ceta-release.jks` and its passwords**: a lost key means no update can ever install over an old build.
