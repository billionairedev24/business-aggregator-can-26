# scripts/ovh/deploy.sh reads helm_values and data_values; scripts/ovh/secrets.sh reads s3_credentials.

output "config_env" {
  description = "Non-secret environment variables (the chart's configEnv)."
  value       = module.ovh.config_env
}

# Everything the northline chart needs from this environment, as a values file:
#   terraform output -json helm_values > ovh-vps-values.json   →   helm upgrade … -f ovh-vps-values.json
# Holds no secret (the secrets are in the Secret northline-secrets, scripts/ovh/secrets.sh).
output "helm_values" {
  description = "Values for deploy/helm/northline: configEnv (layered after values-dryrun.yaml and values-ovh-vps.yaml)."
  value       = { configEnv = module.ovh.config_env, global = { hostingCountry = local.loc.country } }
}

output "location" {
  description = "The location (S-160) and what it derived: country (data residency), API endpoint, token page, Object Storage endpoint and region, the VPS datacentre to order in, whether the Network Firewall is used."
  value       = merge(module.ovh.location, { edge_firewall_used = local.edge_firewall })
}

output "data_values" {
  description = "Values for deploy/helm/northline-data (terraform output -json data_values > ovh-vps-data-values.json)."
  value       = module.ovh.data_values
}

output "storage" {
  description = "Object Storage buckets, S3 endpoint and region, access key ids."
  value       = module.ovh.storage
}

output "s3_credentials" {
  description = "S3 keys of the uploads and Postgres-backup users (read by scripts/ovh/secrets.sh)."
  sensitive   = true
  value       = module.ovh.s3_credentials
}

output "vps" {
  description = "The server (an instance or a VPS — the name stays vps for the scripts): kind, service name or instance id, flavor, IPv4 (the DNS records' target and the SSH host), whether the Network Firewall is on, who may SSH (read by scripts/ovh/bootstrap-k3s.sh and ci.sh)."
  value = {
    server        = var.server
    service_name  = local.instance ? ovh_cloud_project_instance.box[0].id : var.vps_service_name
    flavor        = local.instance ? var.instance_flavor : null
    ipv4          = local.vps_ipv4
    edge_firewall = length(ovh_ip_firewall.box) > 0
    ssh           = local.vps_ipv4 == null ? null : "ubuntu@${local.vps_ipv4}"
    admin_cidrs   = var.admin_cidrs
  }
}

output "dns_records" {
  description = "A records to create at the DNS host of the zone (docs/runbooks/ovh.md § DNS): every public host → the VPS."
  value       = local.vps_ipv4 == null ? [] : [for h in [var.dns_zone_name, "*.${var.dns_zone_name}"] : "${h}. 300 IN A ${local.vps_ipv4}"]
}

output "in_cluster" {
  description = "Which data services run on the VPS."
  value       = module.ovh.in_cluster
}
