# Deploy free on Hugging Face Spaces + Aiven MySQL, release on GitHub

No card is needed. The Space (Docker SDK, public) runs the backend; Aiven's free plan hosts MySQL. Spaces sleeps after about 48 hours without a request and wakes on the next one (the booking sweeper catches up). Aiven's free MySQL is switched off after a stretch of inactivity: open its console now and then.

## 1. MySQL on Aiven
1. <https://console.aiven.io> > create a service > **MySQL** > plan **Free** (any cloud/region).
2. When it is running, copy **Host, Port, User, Password** (database `defaultdb`). JDBC URL for the backend:
   `jdbc:mysql://HOST:PORT/defaultdb?sslMode=REQUIRED&serverTimezone=UTC`
   Flyway builds the schema and the seeder adds the demo catalogue on first start.

## 2. Space
1. <https://huggingface.co/new-space>: name `ceta-backend`, SDK **Docker** (Blank), visibility **Public** (the app must reach it), hardware **CPU basic (free)**.
2. Put the two files of `deploy/huggingface/` into the Space repo (`README.md` and `Dockerfile`: web upload via *Files > Add file*, or `git clone` the Space and copy them in).
3. Space > **Settings > Variables and secrets** (secrets for passwords):

| Name | Value |
|---|---|
| `DATABASE_URL` | the JDBC URL from step 1 |
| `DATABASE_USER`, `DATABASE_PASSWORD` | from Aiven (secret) |
| `JWT_SECRET` | `openssl rand -hex 32` (secret) |
| `PUBLIC_BASE_URL` | `https://<hf-user>-ceta-backend.hf.space` (no trailing slash) |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | first admin login (secret). Without `ADMIN_PASSWORD` no admin is created |
| `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` | VNPay **sandbox** values (optional; without them paying fails) |

4. The Space builds (about 5-8 minutes) and starts. Check: `curl https://<hf-user>-ceta-backend.hf.space/api/v1/movies` answers a JSON list.
   After pushing new code to GitHub: Space > Settings > **Factory rebuild**.
5. VNPay IPN URL to register in the sandbox portal: `https://<hf-user>-ceta-backend.hf.space/api/v1/payments/vnpay/ipn`.
The dev admin `admin@gmail.com / admin@123` is **not** created in `prod`.

## 3. Android release (GitHub Releases)
Secrets in the Android repo (`CETA_KEYSTORE_B64`, `CETA_KEYSTORE_PASSWORD`, `CETA_KEY_PASSWORD`, `CETA_KEY_ALIAS`) are already set.
Set the repository variable `API_BASE_URL` = `https://<hf-user>-ceta-backend.hf.space/api/v1/`, then `git tag v1.0.0 && git push origin v1.0.0`:
the Release workflow tests, builds the signed prod APK and publishes it with a checksum.
**Back up `~/ceta-release.jks` and its passwords**: a lost key means no update can ever install over an old build.
Firebase sign-in needs no server credentials; the release certificate is already registered on `com.ceta.cinema`.


## Alternative: one VM with docker compose
`deploy/docker-compose.yml` (MySQL + backend + Caddy HTTPS) runs on any VM with a domain name, for example Oracle Cloud Always Free (when it has capacity).
