# Deploy on Oracle Cloud Always Free, release on GitHub

One free VM runs everything (`deploy/docker-compose.yml`): MySQL 8, the backend (Docker image, profile `prod`) and Caddy for HTTPS.
It never sleeps. Android release builds refuse plain http, so a domain name with a real certificate is required.

## 1. VM
1. Create an Oracle Cloud account (a card is needed to verify it; the Always Free resources are not charged) and pick the home region carefully, it cannot be changed.
2. Compute > Instances > Create: image **Ubuntu 22.04**, shape **VM.Standard.A1.Flex** (Ampere ARM, 2 OCPU / 12 GB is plenty; if "out of capacity", retry later or try another availability domain). Add your SSH public key. Note the public IP.
3. Network: the VCN's security list (or the instance's NSG) needs ingress rules for TCP **80** and **443** from `0.0.0.0/0`.
4. On the VM, Ubuntu's own firewall also blocks them:
   ```
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```

## 2. Domain
Free option: <https://www.duckdns.org> gives `something.duckdns.org`; point it at the VM's public IP.

## 3. Run
```
curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER   # then log out and back in
git clone -b dev https://github.com/Chunporo/ceta-movie-ticket-backend.git && cd ceta-movie-ticket-backend/deploy
cp .env.example .env && nano .env        # DOMAIN, passwords, JWT_SECRET (openssl rand -hex 32), ADMIN_PASSWORD, VNPay sandbox values
docker compose up -d --build
```
The first build takes several minutes. Flyway creates the schema and the seeder adds the demo catalogue on first start.
Check: `curl https://$DOMAIN/api/v1/movies` answers a JSON list. The dev admin `admin@gmail.com / admin@123` is **not** created in `prod`; the admin is `ADMIN_EMAIL` with `ADMIN_PASSWORD`.
Update later: `git pull && docker compose up -d --build`. Logs: `docker compose logs -f backend`.

## 4. Payments
Only VNPay **sandbox** values go in `.env`. The return URL is built from `DOMAIN`; register `https://$DOMAIN/api/v1/payments/vnpay/ipn` as the IPN URL in the VNPay merchant portal.

## 5. Android release (GitHub Releases)
Secrets in the Android repo (`CETA_KEYSTORE_B64`, `CETA_KEYSTORE_PASSWORD`, `CETA_KEY_PASSWORD`, `CETA_KEY_ALIAS`) are already set.
Set the repository variable `API_BASE_URL` = `https://<DOMAIN>/api/v1/`, then `git tag v1.0.0 && git push origin v1.0.0`:
the Release workflow tests, builds the signed prod APK and publishes it with a checksum.
**Back up `~/ceta-release.jks` and its passwords**: a lost key means no update can ever install over an old build.
Firebase sign-in needs no server credentials; the release certificate is already registered on `com.ceta.cinema`.
