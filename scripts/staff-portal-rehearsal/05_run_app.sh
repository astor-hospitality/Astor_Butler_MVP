#!/usr/bin/env bash
# Step 5, LOCAL ONLY: start the application from this checkout against the migrated copy with the
# staff portal switched on and the local Keycloak as its issuer. Runs in the foreground; stop with Ctrl+C.
#
# Needs: the copy after 04_apply.sh, `docker compose --profile app up -d redis mongo kafka` from
# compose.yaml, and 05_identity_prepare.sh. Java 25 in JAVA_HOME; REHEARSAL_MVN names Maven if it is
# not on PATH. Telegram, the LLM warm-up, the channel ingest and the start notification are off, and
# the JVM trusts only the local certificate, so the application cannot open a TLS connection to anything else.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
prepare_out

: "${REHEARSAL_MVN:=mvn}"
: "${REHEARSAL_APP_PORT:=28088}"
: "${REHEARSAL_PG_PUBLISHED_PORT:=25434}"
: "${REHEARSAL_REDIS_PUBLISHED_PORT:=26379}"
: "${REHEARSAL_MONGO_PUBLISHED_PORT:=27117}"
: "${REHEARSAL_KAFKA_PUBLISHED_PORT:=29092}"
: "${REHEARSAL_KEYCLOAK_PUBLISHED_PORT:=28443}"
identity="$REHEARSAL_OUT/identity"
[ -f "$identity/truststore.p12" ] || die "run 05_identity_prepare.sh first"
issuer="https://localhost:$REHEARSAL_KEYCLOAK_PUBLISHED_PORT/realms/astor"

# The repository's own local defaults (docker-compose.yml); the base is the copy, never the source.
export POSTGRES_URL="jdbc:postgresql://localhost:$REHEARSAL_PG_PUBLISHED_PORT/$REHEARSAL_COPY_DB"
export POSTGRES_USER="$REHEARSAL_PGUSER" POSTGRES_PASSWORD="$REHEARSAL_PGPASSWORD"
export MONGODB_URI="mongodb://oracle:astor_unlock@localhost:$REHEARSAL_MONGO_PUBLISHED_PORT/aether?authSource=admin"
export REDIS_HOST=localhost REDIS_PORT="$REHEARSAL_REDIS_PUBLISHED_PORT"
export KAFKA_BOOTSTRAP_SERVERS="localhost:$REHEARSAL_KAFKA_PUBLISHED_PORT"
export S3_ACCESS_KEY=astor S3_SECRET_KEY=astor_minio_password
export SERVER_PORT="$REHEARSAL_APP_PORT"
export TELEGRAM_BOT_ENABLED=false LLM_WARMUP_ENABLED=false
export ASTOR_AERIS_CHANNEL_INGEST_ENABLED=false ASTOR_AERIS_CHANNEL_INGEST_SCHEDULED_ENABLED=false
export ASTOR_STARTUP_ADMIN_NOTIFICATION_ENABLED=false TELEGRAM_SYSTEM_NOTIFICATIONS_ENABLED=false
# REHEARSAL_STAFF_ENABLED=false starts the same application with the portal off, as it is deployed today.
unset ASTOR_STAFF_ENABLED ASTOR_STAFF_ISSUER_URI ASTOR_STAFF_JWK_SET_URI SPRING_PROFILES_ACTIVE
if [ "${REHEARSAL_STAFF_ENABLED:-true}" = "true" ]; then
  export ASTOR_STAFF_ENABLED=true
  export ASTOR_STAFF_ISSUER_URI="$issuer"
  export ASTOR_STAFF_JWK_SET_URI="$issuer/protocol/openid-connect/certs"
fi

cd "$REPO_DIR"
exec "$REHEARSAL_MVN" -B -ntp spring-boot:run \
  "-Dspring-boot.run.jvmArguments=-Djavax.net.ssl.trustStore=$(host_path "$identity")/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=$(cat "$identity/truststore-password")"
