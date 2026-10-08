# Read-only lookups so the operator can fill zone/flavor/image/disk type from real
# catalog values instead of guessing names. Free: data sources create nothing.

data "cloudru_evolution_compute_zone_collection" "all" {
  project_id = var.project_id
}

data "cloudru_evolution_compute_flavor_collection" "all" {
  project_id = var.project_id
  page_size  = 200
}

data "cloudru_evolution_compute_image_collection" "all" {
  project_id = var.project_id
  page_size  = 200
  filter     = "ubuntu"
}

data "cloudru_evolution_compute_disk_type_collection" "all" {
  project_id = var.project_id
}
