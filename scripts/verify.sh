#!/usr/bin/env bash
# Chạy TRƯỚC khi báo "xong". Không xanh thì không được commit.
set -uo pipefail
fail=0

echo "== 1. Pattern bị cấm =="
if grep -rnE 'redisTemplate\.keys|inMemoryFallback|anyRequest\(\)\s*\.\s*hasRole|ddl-auto[=: ]+update|System\.out\.println' src/ 2>/dev/null; then
  echo "FAIL: còn pattern bị cấm"; fail=1
else echo "ok"; fi

echo "== 2. Không còn tầng hold cũ =="
if grep -rln 'SeatHold\|SeatReservationService\|holdSeats' src/ 2>/dev/null; then
  echo "FAIL: tầng hold cũ còn sót"; fail=1
else echo "ok"; fi

echo "== 3. Không sửa file cấm =="
for f in AGENTS.md docs/BASELINE.md src/main/resources/db/migration/V1__baseline.sql; do
  # T1 creates V1 for the first time. Once tracked, it remains immutable.
  if [ "$f" = "src/main/resources/db/migration/V1__baseline.sql" ] && [ -f "$f" ] &&
     ! git ls-files --error-unmatch -- "$f" >/dev/null 2>&1; then
    echo "ok: new V1 baseline (T1); tracked migrations remain protected"
    continue
  fi
  if [ -f "$f" ] && [ -n "$(git status --porcelain -- "$f" 2>/dev/null)" ]; then
    echo "FAIL: $f bị sửa"; fail=1
  fi
done

echo "== 4. Build + test =="
if command -v mvn >/dev/null 2>&1; then MVN="mvn"
elif [ -f ./mvnw ]; then MVN="sh ./mvnw"
else MVN=""; fi
if [ -n "$MVN" ]; then
  $MVN -q clean test || fail=1
else
  echo "FAIL: không có mvn lẫn ./mvnw"; fail=1
fi

echo "== 5. Test đồng thời (chỉ khi có MySQL) =="
if [ -n "${IT_MYSQL_URL:-}" ]; then
  $MVN -q -Pit-mysql test -Dtest=ConcurrentClaimTest || fail=1
else
  echo "SKIP: IT_MYSQL_URL chưa đặt (T3 phải chạy có MySQL)"
fi

echo "----------------------------------------"
if [ "$fail" -eq 0 ]; then echo "VERIFY: PASS"; else echo "VERIFY: FAIL"; fi
exit "$fail"
