#!/usr/bin/env bash
# Step 5, LOCAL ONLY: a throwaway Keycloak for Astor on this machine.
#
# Makes, outside the repository (REHEARSAL_OUT/identity):
#   tls/tls.crt, tls/tls.key   self-signed certificate for localhost, valid 7 days
#   truststore.p12             that certificate for the application JVM (javax.net.ssl.trustStore)
#   realm/astor-realm.json     docker/keycloak/astor-realm.json of the target ref with the callback
#                              and origin of the cabinet replaced by local ones, so that no step can
#                              be redirected to the real site
#   admin-password, truststore-password   random, generated here, never printed
# and starts the keycloak service of compose.yaml, waiting until the realm answers.
#
# This is not the identity stack of docker/keycloak/ (production mode, own PostgreSQL, secrets):
# it is development mode with an in-memory database and exists to exercise the application.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
prepare_out

: "${REHEARSAL_KEYCLOAK_PUBLISHED_PORT:=28443}"
identity="$REHEARSAL_OUT/identity"
origin="https://localhost:$REHEARSAL_KEYCLOAK_PUBLISHED_PORT"
callback="$origin/rehearsal-callback/"
mkdir -p "$identity/tls" "$identity/realm"
umask 077

if [ ! -f "$identity/tls/tls.crt" ]; then
  (cd "$identity/tls" && MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -days 7 \
    -keyout tls.key -out tls.crt -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1)
  # The container runs as uid 1000 and must be able to read the key of this throwaway certificate.
  chmod 644 "$identity/tls/tls.key" "$identity/tls/tls.crt"
fi
[ -f "$identity/admin-password" ] || openssl rand -hex 24 >"$identity/admin-password"
[ -f "$identity/truststore-password" ] || openssl rand -hex 16 >"$identity/truststore-password"
if [ ! -f "$identity/truststore.p12" ]; then
  command -v keytool >/dev/null || die "keytool not found: put the JDK's bin on PATH"
  (cd "$identity" && keytool -importcert -noprompt -alias astor-rehearsal -file tls/tls.crt \
    -keystore truststore.p12 -storetype PKCS12 -storepass "$(cat truststore-password)" >/dev/null 2>&1)
fi

git -C "$REPO_DIR" -c core.autocrlf=false show "$REHEARSAL_TARGET_REF:docker/keycloak/astor-realm.json" \
  | sed -e "s#https://c3ag.ru/astor/staff/#$callback#g" -e "s#\"https://c3ag.ru\"#\"$origin\"#g" >"$identity/realm/astor-realm.json"
if grep -q 'c3ag.ru' "$identity/realm/astor-realm.json"; then die "the local realm copy still names the real site"; fi

REHEARSAL_IDENTITY_DIR="$(host_path "$identity")" REHEARSAL_KC_ADMIN_PASSWORD="$(cat "$identity/admin-password")" \
  docker compose -p astor-rehearsal -f "$REHEARSAL_DIR/compose.yaml" --profile identity up -d keycloak >&2

log "waiting for $origin/realms/astor"
for _ in $(seq 1 90); do
  if curl -s -o /dev/null --cacert "$identity/tls/tls.crt" "$origin/realms/astor/.well-known/openid-configuration" -w '%{http_code}' 2>/dev/null | grep -q '^200$'; then
    echo "issuer: $origin/realms/astor"
    echo "callback registered for astor-staff-ui: $callback"
    echo "identity files: $identity"
    exit 0
  fi
  sleep 2
done
die "Keycloak did not answer in 3 minutes: docker logs astor-rehearsal-keycloak"
