variable "project_id" {
  description = "Cloud.ru Evolution project (console → project settings)."
  type        = string
}

variable "cloudru_key_id" {
  description = "Service-account access key id. Supply via TF_VAR_cloudru_key_id, never in a file."
  type        = string
  sensitive   = true
}

variable "cloudru_secret" {
  description = "Service-account access key secret. Supply via TF_VAR_cloudru_secret, never in a file."
  type        = string
  sensitive   = true
}

variable "object_storage_tenant_id" {
  description = "Object Storage tenant id (console → Object Storage → API parameters). Empty skips the bucket."
  type        = string
  default     = ""
}

variable "name" {
  description = "Prefix for every resource; one stand = one prefix."
  type        = string
  default     = "astor-aeris"
}

variable "zone_name" {
  description = "Availability zone name; list them with `terraform output candidate_zones` after a first `plan`."
  type        = string
}

variable "flavor_name" {
  description = "VM flavor name (4 vCPU / 16 GB is the documented first AERIS size); list with `terraform output candidate_flavors`."
  type        = string
}

variable "image_id" {
  description = "Ubuntu 24.04 image id from `terraform output candidate_images`."
  type        = string
}

variable "disk_type_name" {
  description = "Boot disk type name from `terraform output candidate_disk_types`."
  type        = string
}

variable "disk_size_gb" {
  description = "Boot disk size. Compose stack with Postgres, Mongo, MinIO and model caches needs room."
  type        = number
  default     = 100
}

variable "ssh_public_key" {
  description = "Operator's SSH public key (one line). The deploy workflow's key is added separately by the operator."
  type        = string
}

variable "admin_cidr" {
  description = "CIDR allowed to reach SSH. Never 0.0.0.0/0 for a production stand."
  type        = string
}

variable "subnet_cidr" {
  type    = string
  default = "10.42.0.0/24"
}

variable "glasses_bucket" {
  description = "Private bucket for the glasses archive (ASTOR_GLASSES_S3_BUCKET). Empty skips it."
  type        = string
  default     = ""
}
