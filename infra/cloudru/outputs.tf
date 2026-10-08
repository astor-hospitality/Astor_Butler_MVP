output "public_ip" {
  description = "Address for DNS (A record) and the deploy workflow's CLOUDRU_VM_HOST secret."
  value       = cloudru_evolution_compute_external_ip.app
}

output "vm_id" {
  value = cloudru_evolution_compute_vm.app.id
}

output "candidate_zones" {
  value = [for z in data.cloudru_evolution_compute_zone_collection.all.zones : z.name if z.enabled]
}

output "candidate_flavors" {
  value = [for f in data.cloudru_evolution_compute_flavor_collection.all.flavors : "${f.name} (${f.cpu} vCPU)" if f.gpu == 0 && f.cpu >= 4]
}

output "candidate_images" {
  value = [for i in data.cloudru_evolution_compute_image_collection.all.images : "${i.id}  ${i.display_name}"]
}

output "candidate_disk_types" {
  value = [for d in data.cloudru_evolution_compute_disk_type_collection.all.disk_types : d.name]
}
