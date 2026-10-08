# Стенд Astor на Cloud.ru Evolution

Terraform для одной ВМ, её сети, firewall и приватного бакета очков. Провайдер `cloudru/cloud` 2.1.3, формы ресурсов — из официального справочника [cloud-ru/evo-terraform](https://github.com/cloud-ru/evo-terraform/tree/main/reference). Порядок переезда целиком — `docs/operations/CLOUDRU_MIGRATION.md`.

## Что создаётся

| Ресурс | Имя | Зачем |
| --- | --- | --- |
| VPC + подсеть | `<name>-vpc`, `<name>-subnet` | 10.42.0.0/24 |
| Security group | `<name>-sg` | вход: 22 только с `admin_cidr`, 80 и 443 отовсюду; выход — любой |
| Диск + ВМ | `<name>-boot`, `<name>-vm` | Ubuntu 24.04, cloud-init ставит Docker, создаёт пользователя `deploy` и `/opt/astor-butler` |
| Интерфейс + публичный IP | `<name>-eth0`, `<name>-ip` | адрес для DNS и для деплой-воркфлоу |
| Бакет | `glasses_bucket` | `materials/` живут сутки, незавершённые multipart — сутки; доступ только по TLS (`GLASSES_S3_STORAGE.md`) |

Ничего платного не создаётся без `terraform apply`. `plan` и data-источники бесплатны.

## Перед apply

1. **Деньги.** На счёте должно быть покрытие хотя бы на месяц ВМ + бакета; грант конкурса не считается зачисленным, пока его не видно в биллинге. Без этого `apply` не делать.
2. Сервисный аккаунт в проекте с ролями на Compute, VPC и Object Storage; ключ доступа (Key ID + Secret) — в менеджер паролей.
3. Terraform ≥ 1.6 на машине оператора. Без brew: скачать бинарник с releases.hashicorp.com в `~/bin` (run.sh добавляет `~/bin` в PATH).
4. Провайдер `cloudru/cloud` не в публичном registry — он на зеркале Cloud.ru. `run.sh` подключает его через `cli.tfrc` (`TF_CLI_CONFIG_FILE`), домашний `~/.terraformrc` не трогается.

## Запуск

```bash
cd infra/cloudru
cp terraform.tfvars.example terraform.tfvars        # git-ignored
export TF_VAR_cloudru_key_id='…' TF_VAR_cloudru_secret='…'   # только в этой оболочке
terraform init
terraform plan                                       # пока без зоны/флейвора упадёт на валидации — это ожидаемо
```

Чтобы заполнить `zone_name`, `flavor_name`, `image_id`, `disk_type_name` реальными значениями каталога:

```bash
terraform apply -target=data.cloudru_evolution_compute_zone_collection.all \
                -target=data.cloudru_evolution_compute_flavor_collection.all \
                -target=data.cloudru_evolution_compute_image_collection.all \
                -target=data.cloudru_evolution_compute_disk_type_collection.all
terraform output candidate_zones candidate_flavors candidate_images candidate_disk_types
```

Это только чтение. Впиши значения в `terraform.tfvars`, затем:

```bash
terraform plan -out=stand.plan   # прочитать целиком: одна ВМ, один диск, один IP, один бакет
terraform apply stand.plan
terraform output public_ip
```

## После apply

1. DNS: A-запись домена → `public_ip`. TTL короткий на время переезда.
2. SSH: `ssh deploy@<ip>` ключом из `ssh_public_key`. Пользователь `deploy` умеет только `docker` через sudo.
3. Секреты: `/opt/astor-butler/.env.production` создать руками от root, `chmod 0600`. Карта переменных — в `CLOUDRU_MIGRATION.md`. Файл не копировать с Яндекса вслепую.
4. Деплой-воркфлоу `Deploy to Yandex VM` не привязан к Яндексу: это SSH + rsync + `scripts/deploy/aeris-scoped.sh`. Для нового стенда достаточно указать в GitHub Environment `production` секреты `*_VM_HOST`, `*_VM_USER=deploy`, `*_VM_SSH_KEY`, `*_VM_DEPLOY_PATH=/opt/astor-butler` на новую ВМ. Переименование секретов и воркфлоу под Cloud.ru — отдельный PR от Codex (создание воркфлоу с секретами из этой сессии заблокировано политикой).
5. `.env.production`: `ASTOR_GLASSES_S3_ENDPOINT=https://s3.cloud.ru`, `ASTOR_GLASSES_S3_ACCESS_KEY=<tenant_id>:<key_id>`, `ASTOR_GLASSES_S3_BUCKET=<glasses_bucket>`.

## Что здесь не описано намеренно

- Managed PostgreSQL / Redis / Kafka Cloud.ru — стенд повторяет текущую схему «всё в Compose на одной ВМ»; выносить БД в managed — отдельное решение после переезда.
- Бэкапы: снапшоты диска в консоли + `pg_dump` в `/opt/astor-butler/backups` по runbook. Terraform их не планирует.
- Старая ВМ Яндекса: не трогается. Удаление — только после приёмки и отдельного разрешения.

## Откат

`terraform destroy` удаляет всё, включая бакет с материалами очков (они и так живут сутки). Состояние `terraform.tfstate` хранить у оператора, не в git; при работе вдвоём — remote state с блокировкой (гайд провайдера `guides/state_lock_gitlab.md`).
