#!/usr/bin/env bash
# Runs the production profile on this machine, on port 8090, against its own MySQL container (ceta-public-mysql, 127.0.0.1:33317).
# Secrets live in ~/.ceta-public.env (MYSQL_PASSWORD, JWT_SECRET, ADMIN_PASSWORD, optional VNPAY_*), never in the repo.
# Public address: `tailscale funnel --bg 8090` gives https://<this-machine>.<tailnet>.ts.net
set -euo pipefail
cd "$(dirname "$0")/../.."
. "$HOME/.ceta-public.env"
[ -f target/ticket-booking-0.0.1-SNAPSHOT.jar ] || sh ./mvnw -B -q -DskipTests package
export SPRING_PROFILES_ACTIVE=prod PORT=8090
export DATABASE_URL="jdbc:mysql://127.0.0.1:33317/ceta?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
export DATABASE_USER=ceta DATABASE_PASSWORD="$MYSQL_PASSWORD"
export JWT_SECRET ADMIN_EMAIL="${ADMIN_EMAIL:-admin@ceta.app}" ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
export PUBLIC_BASE_URL="${PUBLIC_BASE_URL:?set PUBLIC_BASE_URL in ~/.ceta-public.env}"
export VNPAY_TMN_CODE="${VNPAY_TMN_CODE:-}" VNPAY_HASH_SECRET="${VNPAY_HASH_SECRET:-}"
exec java -XX:+UseSerialGC -jar target/ticket-booking-0.0.1-SNAPSHOT.jar
