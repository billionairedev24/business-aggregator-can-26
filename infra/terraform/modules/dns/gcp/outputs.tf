output "zone_id" {
  description = "Managed zone id."
  value       = google_dns_managed_zone.this.id
}

output "zone_name" {
  description = "Zone name."
  value       = var.zone_name
}

output "name_servers" {
  description = "NS records to create in the parent zone / at the registrar."
  value       = google_dns_managed_zone.this.name_servers
}

output "cloud" {
  description = "Google Cloud-only details."
  value       = { managed_zone = google_dns_managed_zone.this.name }
}

# S-17: what the edge add-ons need to write this zone (docs/runbooks/edge.md). The identities come from the
# kubernetes module (GKE Workload Identity), so nothing here is a credential.
output "cert_manager_dns01" {
  description = "cert-manager ACME DNS-01 solver block for this zone (Issuer spec.acme.solvers[].dns01)."
  value = {
    cloudDNS = { project = var.context.project_id, hostedZoneName = google_dns_managed_zone.this.name }
  }
}

output "external_dns" {
  description = "external-dns Helm values that point it at this zone."
  value = {
    provider      = { name = "google" }
    domainFilters = [var.zone_name]
    env           = []
    extraArgs     = ["--google-project=${var.context.project_id}", "--google-zone-visibility=public"]
  }
}
