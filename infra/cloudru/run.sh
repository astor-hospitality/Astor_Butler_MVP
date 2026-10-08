#!/usr/bin/env bash
# Operator-side runner: reads the catalog, then prepares a plan. Never prints secrets.
#
#   bash infra/cloudru/run.sh catalog   # free: zones, flavors, images, disk types → .out/catalog.json
#   bash infra/cloudru/run.sh plan      # free: terraform plan → .out/plan.txt (nothing is created)
#   bash infra/cloudru/run.sh apply     # PAID: applies stand.plan from the previous step
#
# Credentials live in one file outside the repository, mode 0600:
#   ~/.config/astor/cloudru.env
#   TF_VAR_cloudru_key_id=...
#   TF_VAR_cloudru_secret=...
# Everything written under .out/ is safe to share: outputs and plans redact sensitive values.
set -euo pipefail
cd "$(dirname "$0")"
stage="${1:-catalog}"
out=.out
mkdir -p "$out"
env_file="${CLOUDRU_ENV:-$HOME/.config/astor/cloudru.env}"
export PATH="$HOME/bin:$PATH"

if ! command -v terraform >/dev/null 2>&1; then
  echo "terraform not found. Install: brew install terraform   (or: brew tap hashicorp/tap && brew install hashicorp/tap/terraform)" | tee "$out/status.txt"
  exit 2
fi
if [ ! -f "$env_file" ]; then
  echo "missing $env_file (TF_VAR_cloudru_key_id / TF_VAR_cloudru_secret)" | tee "$out/status.txt"
  exit 2
fi
set -a; . "$env_file"; set +a
# The provider comes from Cloud.ru's mirror, declared next to this script; the user's ~/.terraformrc stays untouched.
export TF_CLI_CONFIG_FILE="$PWD/cli.tfrc"
: "${TF_VAR_cloudru_key_id:?empty in $env_file}" "${TF_VAR_cloudru_secret:?empty in $env_file}"
case "$TF_VAR_cloudru_key_id$TF_VAR_cloudru_secret" in *ВСТАВЬ*) echo "placeholders still in $env_file: paste the real key_id and secret" | tee "$out/status.txt"; exit 2;; esac
[ -f terraform.tfvars ] || cp terraform.tfvars.example terraform.tfvars

terraform version -no-color > "$out/version.txt"
if ! terraform init -input=false -no-color > "$out/init.txt" 2>&1; then
  echo "init failed, see .out/init.txt" | tee "$out/status.txt"; tail -20 "$out/init.txt"; exit 1
fi

case "$stage" in
  catalog)
    targets=(-target=data.cloudru_evolution_compute_zone_collection.all
             -target=data.cloudru_evolution_compute_flavor_collection.all
             -target=data.cloudru_evolution_compute_image_collection.all
             -target=data.cloudru_evolution_compute_disk_type_collection.all)
    if terraform apply -input=false -auto-approve -no-color "${targets[@]}" > "$out/catalog-apply.txt" 2>&1; then
      terraform output -no-color -json > "$out/catalog.json"
      echo "catalog ok: $(date -u +%FT%TZ)" | tee "$out/status.txt"
    else
      echo "catalog failed, see .out/catalog-apply.txt" | tee "$out/status.txt"; tail -30 "$out/catalog-apply.txt"; exit 1
    fi ;;
  plan)
    if terraform plan -input=false -no-color -out=stand.plan > "$out/plan.txt" 2>&1; then
      terraform show -no-color stand.plan > "$out/plan-show.txt"
      echo "plan ok: $(date -u +%FT%TZ)" | tee "$out/status.txt"
    else
      echo "plan failed, see .out/plan.txt" | tee "$out/status.txt"; tail -40 "$out/plan.txt"; exit 1
    fi ;;
  apply)
    [ -f stand.plan ] || { echo "no stand.plan: run 'plan' first" | tee "$out/status.txt"; exit 2; }
    echo "This creates PAID resources. Type APPLY to continue:"; read -r confirm
    [ "$confirm" = APPLY ] || { echo "cancelled" | tee "$out/status.txt"; exit 0; }
    if terraform apply -input=false -no-color stand.plan > "$out/apply.txt" 2>&1; then
      terraform output -no-color -json > "$out/outputs.json"
      echo "apply ok: $(date -u +%FT%TZ)" | tee "$out/status.txt"
    else
      echo "apply failed, see .out/apply.txt" | tee "$out/status.txt"; tail -40 "$out/apply.txt"; exit 1
    fi ;;
  *) echo "usage: run.sh catalog|plan|apply"; exit 2 ;;
esac
