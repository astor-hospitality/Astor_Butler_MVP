# One VM, its network, a firewall that opens only what the stand serves, and the
# glasses' private bucket. Resource shapes follow cloud-ru/evo-terraform reference 2.1.3.

locals {
  zone = { name = var.zone_name }
}

# --- Network -----------------------------------------------------------------

resource "cloudru_evolution_vpc_vpc" "main" {
  project_id  = var.project_id
  name        = "${var.name}-vpc"
  description = "Astor Butler stand"
}

resource "cloudru_evolution_compute_subnet" "main" {
  project_id     = var.project_id
  zone           = local.zone
  name           = "${var.name}-subnet"
  vpc_id         = cloudru_evolution_vpc_vpc.main.id
  subnet_address = var.subnet_cidr
  default        = true
  routed_network = true
}

resource "cloudru_evolution_compute_security_group" "web" {
  project_id  = var.project_id
  zone        = local.zone
  name        = "${var.name}-sg"
  description = "SSH from the operator, HTTP/TLS from everyone, nothing else"
}

locals {
  ingress = {
    ssh   = { port = "22", cidr = var.admin_cidr, note = "SSH, operator only" }
    http  = { port = "80", cidr = "0.0.0.0/0", note = "HTTP → api-gateway / ACME" }
    https = { port = "443", cidr = "0.0.0.0/0", note = "TLS" }
  }
}

resource "cloudru_evolution_compute_security_group_rule" "ingress" {
  for_each          = local.ingress
  security_group_id = cloudru_evolution_compute_security_group.web.id
  description       = each.value.note
  direction         = "TRAFFIC_DIRECTION_INGRESS"
  ether_type        = "ETHER_TYPE_IPV4"
  ip_protocol       = "IP_PROTOCOL_TCP"
  port_range        = each.value.port
  remote_ip_prefix  = each.value.cidr
}

resource "cloudru_evolution_compute_security_group_rule" "egress" {
  security_group_id = cloudru_evolution_compute_security_group.web.id
  description       = "Telegram, Foundation Models, S3, apt, Docker Hub"
  direction         = "TRAFFIC_DIRECTION_EGRESS"
  ether_type        = "ETHER_TYPE_IPV4"
  ip_protocol       = "IP_PROTOCOL_ANY"
  port_range        = "1-65535"
  remote_ip_prefix  = "0.0.0.0/0"
}

# --- Machine -----------------------------------------------------------------

resource "cloudru_evolution_compute_disk" "boot" {
  project_id = var.project_id
  zone       = local.zone
  name       = "${var.name}-boot"
  size       = var.disk_size_gb
  bootable   = true
  disk_type  = { name = var.disk_type_name }
  image      = { id = var.image_id }
}

resource "cloudru_evolution_compute_vm" "app" {
  project_id  = var.project_id
  zone        = local.zone
  name        = "${var.name}-vm"
  description = "Astor Butler: api-gateway, AERIS bot, glasses adapter, Postgres, Mongo, MinIO"
  flavor      = { name = var.flavor_name }
  disks       = [{ id = cloudru_evolution_compute_disk.boot.id }]

  # Ubuntu images require the `authentication` metadata group: a public key or a password. Key only.
  image_metadata = {
    public_key = { string_value = var.ssh_public_key }
  }

  cloud_init_userdata = templatefile("${path.module}/cloud-init.yaml", {
    ssh_public_key = var.ssh_public_key
  })

  timeouts {
    create = "30m"
    update = "20m"
    delete = "20m"
  }
  lifecycle {
    ignore_changes = [timeouts, cloud_init_userdata]
  }
}

resource "cloudru_evolution_compute_interface" "app" {
  project_id                 = var.project_id
  zone                       = local.zone
  name                       = "${var.name}-eth0"
  type                       = "INTERFACE_TYPE_REGULAR"
  interface_security_enabled = true
  vm                         = { id = cloudru_evolution_compute_vm.app.id }
  subnet                     = { id = cloudru_evolution_compute_subnet.main.id }
  security_groups            = [{ id = cloudru_evolution_compute_security_group.web.id }]
}

resource "cloudru_evolution_compute_external_ip" "app" {
  project_id        = var.project_id
  zone              = local.zone
  name              = "${var.name}-ip"
  network_interface = { id = cloudru_evolution_compute_interface.app.id }
}

# --- Glasses archive ----------------------------------------------------------
# Mirrors docs/operations/GLASSES_S3_STORAGE.md: materials/ expire after a day,
# incomplete multipart uploads are dropped after a day, documents keep no lifecycle.

resource "cloudru_evolution_obs_bucket" "glasses" {
  count      = var.glasses_bucket != "" && var.object_storage_tenant_id != "" ? 1 : 0
  bucket     = var.glasses_bucket
  versioning = false

  lifecycle_rules = [
    {
      name       = "expire-materials"
      enabled    = true
      expiration = { days = 1 }
      filter     = { prefix = "materials/" }
    },
    {
      name                              = "abort-multipart"
      enabled                           = true
      abort_incomplete_multipart_upload = { days = 1 }
      filter                            = { prefix = "" }
    }
  ]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Deny"
      Principal = "*"
      Action    = ["s3:GetObject", "s3:ListBucket"]
      Resource  = ["arn:aws:s3:::${var.glasses_bucket}", "arn:aws:s3:::${var.glasses_bucket}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })

  tags = [
    { key = "service", value = "astor-glasses" },
    { key = "stand", value = var.name }
  ]
}
