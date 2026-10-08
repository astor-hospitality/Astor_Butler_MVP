#!/usr/bin/env bash
# Switch Astor bot and Vedal portal to direct Sber keys (GigaChat API + SaluteSpeech).
# Input: ~ubuntu/.sber.env with GIGACHAT_AUTH_KEY=... and/or SALUTE_AUTH_KEY=... (optional GIGACHAT_SCOPE, SALUTE_SCOPE).
# The input file is deleted after it is applied. Safe to re-run.
set -euo pipefail
IN=/home/ubuntu/.sber.env; [ -f "$IN" ] || { echo "no $IN"; exit 1; }
get(){ sed -n "s/^$1=//p" "$IN" | tail -1; }
GK=$(get GIGACHAT_AUTH_KEY); SK=$(get SALUTE_AUTH_KEY); GS=$(get GIGACHAT_SCOPE); SS=$(get SALUTE_SCOPE)
GS=${GS:-GIGACHAT_API_PERS}; SS=${SS:-SALUTE_SPEECH_PERS}
setkv(){ f=$1; kv=$2; k=${kv%%=*}; grep -q "^$k=" "$f" && sed -i "s|^$k=.*|$kv|" "$f" || echo "$kv" >> "$f"; }
A=/opt/astor-butler/.env.production; V=/opt/vedal-portal/backend/.env; G=/opt/astor-glasses/private/runtime.env
umask 077
if [ -n "$GK" ]; then
  for kv in "ASTOR_MODEL_PROVIDER=gigachat" "GIGACHAT_AUTH_KEY=$GK" "GIGACHAT_SCOPE=$GS" "GIGACHAT_MODEL=GigaChat-2-Max" "GIGACHAT_QUALITY_MODEL=GigaChat-2-Max" "GIGACHAT_VISION_MODEL=GigaChat-2-Max" "GIGACHAT_EMBEDDING_MODEL=Embeddings" "GIGACHAT_CA_CERT_PATH=/app/certs/russian_trusted_root_ca.pem"; do setkv $A "$kv"; done
  for kv in "VEDAL_LLM_PROVIDER=gigachat" "GIGACHAT_AUTH_KEY=$GK" "GIGACHAT_SCOPE=$GS" "GIGACHAT_MODEL=GigaChat-2-Max" "GIGACHAT_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem"; do setkv $V "$kv"; done
  echo "gigachat: bot + vedal configured"
fi
if [ -n "$SK" ]; then
  for kv in "SALUTE_AUTH_KEY=$SK" "SALUTE_SCOPE=$SS" "SALUTE_CA_CERT_PATH=/app/certs/russian_trusted_root_ca.pem" "ASTOR_TTS_PROVIDER=salute" "ASTOR_TTS_WEB_ENABLED=true" "SALUTE_TTS_FORMAT=opus" "ASTOR_TELEGRAM_VOICE_REPLIES=auto"; do setkv $A "$kv"; done
  for kv in "SALUTE_AUTH_KEY=$SK" "SALUTE_SCOPE=$SS" "SALUTE_CA_CERT_PATH=/certs/russian_trusted_root_ca.pem" "ASTOR_GLASSES_TTS_ENABLED=true" "ASTOR_GLASSES_TTS_PROVIDER=salute"; do setkv $G "$kv"; done
  for kv in "VEDAL_TTS_PROVIDER=salute" "SALUTE_AUTH_KEY=$SK" "SALUTE_SCOPE=$SS" "SALUTE_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem"; do setkv $V "$kv"; done
  echo "salute: bot + glasses + vedal configured"
fi
rm -f "$IN"
echo "now rebuild: vedal portal (certs are baked into /app/certs) and restart bot/glasses with the cert volume"
