#!/usr/bin/env bash
# Nightly Astor backup: Postgres (custom format, verified by pg_restore --list) + Mongo archive,
# kept 7 days on the VM and copied to Cloud.ru Object Storage s3://astor-backups/db/.
set -euo pipefail
umask 077
D=${ASTOR_BACKUP_DIR:-/var/backups/astor/daily}
install -d -m 700 "$D"
ts=$(date +%Y%m%d-%H%M%S)
pg="$D/astor-pg-$ts.dump"; mg="$D/astor-mongo-$ts.archive.gz"
docker exec astor_postgres_test sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > "$pg"
tables=$(docker exec -i astor_postgres_test pg_restore --list < "$pg" | grep -c 'TABLE DATA' || true)
[ "$(stat -c %s "$pg")" -gt 1024 ] && [ "$tables" -gt 0 ] || { echo "ERROR: postgres dump looks empty ($tables table sections)"; exit 1; }
echo "postgres: $(du -h "$pg" | cut -f1), $tables table sections"
docker exec astor_mongo_test sh -c 'mongodump --quiet --archive --gzip --authenticationDatabase admin -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD"' > "$mg"
echo "mongo: $(du -h "$mg" | cut -f1)"
upload_failed=0
for f in "$pg" "$mg"; do
  if docker run --rm --env-file /home/ubuntu/.s3-cloudru.env -v "$D:/d:ro" amazon/aws-cli:2.22.35 \
    --endpoint-url https://s3.cloud.ru s3 cp --quiet "/d/$(basename "$f")" "s3://astor-backups/db/$(basename "$f")" \
  ; then
    echo "uploaded $(basename "$f")"
  else
    echo "ERROR: upload failed for $(basename "$f"), copy stays on the VM" >&2
    upload_failed=1
  fi
done
if [ "$upload_failed" -ne 0 ]; then
  echo "ERROR: remote backup incomplete; local retention cleanup skipped" >&2
  exit 1
fi
find "$D" -maxdepth 1 -type f \( -name 'astor-pg-*.dump' -o -name 'astor-mongo-*.archive.gz' \) -mtime +7 -delete
