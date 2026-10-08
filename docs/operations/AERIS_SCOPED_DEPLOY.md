> Deprecated 2026-10-08: production moves to the Cloud.ru VM, see
> `CLOUDRU_DEPLOY_RUNBOOK.md`. The `Deploy to Yandex VM` workflow keeps its file but its
> triggers are disabled; the guards below still describe `scripts/deploy/aeris-scoped.sh`.

# Scoped AERIS deployment

Deploy to Yandex VM defaults to `preflight`: validates the existing VM Compose
configuration, running AERIS container and health endpoint without changing it.
The workflow must run from `main`. Deploy additionally requires successful CI
for the exact revision, `confirm=DEPLOY_AERIS`, and `backup_path` pointing to a
nonempty backup less than 24 hours old. The operator must verify the backup,
restore procedure, migration compatibility and maintenance window separately.

Sources are uploaded to `releases/<sha>` without deleting existing VM files.
The image receives a revision-specific tag. The existing VM Compose files and
environment remain authoritative: new environment/Compose changes require
separate review. Only `aeris-astor-butler-bot` is recreated with
`--no-deps --no-build --pull never`; PostgreSQL, Redis, gateway, Keycloak,
VEDAL/C3AG and other bots are not started or rebuilt.

Failed container health triggers restoration of the previous image ID saved in
`releases/<sha>/previous-image.txt`. This does **not** revert database migrations.
Do not deploy incompatible migrations assuming image rollback restores the DB.
Do not subsequently run unscoped Compose up: the base Compose image tag remains
unchanged. All later AERIS updates must use this scoped workflow.

The workflow still uses the existing ssh-keyscan trust model; pinning a verified
host key is a separate hardening task. CI/HTTP health are not proof of Telegram
polling or guest scenarios: verify those separately after the deployment.
Staff/Keycloak enablement and live Saby writes are excluded from this release.

Local verification: `bash scripts/deploy/test-aeris-scoped.sh` (mocked Docker,
no real containers, migrations or HTTP requests).
