output "network_id" {
  description = "VPC id (projects/<p>/global/networks/<name>)."
  value       = google_compute_network.this.id
}

output "network_name" {
  description = "VPC name."
  value       = google_compute_network.this.name
}

output "cidr" {
  description = "Address space the subnets are carved from."
  value       = var.cidr
}

output "cluster_subnet_ids" {
  description = "Regional subnet for GKE nodes (secondary ranges pods / services)."
  value       = [google_compute_subnetwork.cluster.id]
}

output "data_subnet_ids" {
  description = "Regional subnet for Private Service Connect endpoints (Memorystore, Managed Kafka)."
  value       = [google_compute_subnetwork.data.id]
}

output "cloud" {
  description = "Google Cloud-only details."
  value = {
    network_self_link      = google_compute_network.this.self_link
    private_service_access = google_service_networking_connection.this.id
    psa_range              = "${google_compute_global_address.private_service_access.address}/${local.psa_prefix}"
    pods_range_name        = "pods"
    services_range_name    = "services"
    pods_cidr              = local.pods_cidr
    nat_public_ips         = google_compute_address.nat[*].address
    memorystore_psc_policy = google_network_connectivity_service_connection_policy.memorystore.id
  }
}
