terraform {
  required_version = ">= 1.6"
  required_providers {
    cloudru = {
      source  = "cloudru/cloud"
      version = "2.1.3"
    }
  }
}

# Credentials never live in this directory. Export them in the operator's shell:
#   export TF_VAR_cloudru_key_id=...  TF_VAR_cloudru_secret=...
# (service-account access key with the Evolution roles the README lists).
provider "cloudru" {
  project_id               = var.project_id
  auth_key_id              = var.cloudru_key_id
  auth_secret              = var.cloudru_secret
  region                   = "ru-central-1"
  object_storage_tenant_id = var.object_storage_tenant_id
}
