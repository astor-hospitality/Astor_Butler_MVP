# One bucket per site/domain in Cloud.ru Object Storage (console → Object Storage → "Параметры работы с API"
# gives the tenant id). Public URL of a bucket: https://<name>.s3.cloud.ru. Nothing here has a lifecycle:
# media and documents are kept until deleted; backups are pruned by the backup job itself.
locals {
  site_buckets = {
    "c3ag-media"      = "c3ag.ru — media (posters, video) for the site"
    "vedal-media"     = "vedal-med.ru — product photos and media"
    "vedal-documents" = "vedal-med.ru — documents uploaded in the portal"
    "vedal-backups"   = "vedal-med.ru — nightly DB dumps"
    "astor-media"     = "Astor backend — media (replaces RustFS on the VM)"
    "astor-documents" = "Astor backend — documents"
  }
}

resource "cloudru_evolution_obs_bucket" "site" {
  for_each   = var.object_storage_tenant_id != "" ? local.site_buckets : {}
  bucket     = each.key
  versioning = false
}

output "site_buckets" {
  value = { for k, b in cloudru_evolution_obs_bucket.site : k => "https://${k}.s3.cloud.ru" }
}
