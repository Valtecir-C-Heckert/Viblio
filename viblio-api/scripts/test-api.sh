#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_python="${VIBLIO_TEST_PYTHON:-python3}"
test_work="$(mktemp -d)"
test_container="viblio-api-test-$(date +%s)-$$"
cleanup() { docker rm -f "$test_container" >/dev/null 2>&1 || true; rm -rf "$test_work"; }
trap cleanup EXIT
"$test_python" - "$test_work" <<'PY'
import pathlib,secrets,sys
root=pathlib.Path(sys.argv[1]);password=secrets.token_hex(24)
(root/'password').write_text(password);(root/'password').chmod(0o600)
(root/'postgres.env').write_text('POSTGRES_DB=viblio_test\nPOSTGRES_USER=viblio\nPOSTGRES_PASSWORD='+password+'\n');(root/'postgres.env').chmod(0o600)
PY
docker run -d --name "$test_container" --env-file "$test_work/postgres.env" -p 127.0.0.1::5432 postgres:17 >/dev/null
for attempt in $(seq 1 40); do
  if docker exec "$test_container" pg_isready -U viblio -d viblio_test >/dev/null 2>&1; then break; fi
  sleep 1
done
test_port="$(docker port "$test_container" 5432/tcp | head -1 | awk -F: '{print $NF}')"
"$test_python" - "$test_work" "$test_port" <<'PY'
import pathlib,sys
root=pathlib.Path(sys.argv[1]);(root/'dsn').write_text('postgresql://viblio:'+ (root/'password').read_text()+'@127.0.0.1:'+sys.argv[2]+'/viblio_test')
(root/'dsn').chmod(0o600)
PY
export VIBLIO_TEST_DATABASE_URL="$(cat "$test_work/dsn")"
export PYTHONPATH=.
"$test_python" -m pytest -q
