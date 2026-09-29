# `terraform output -json config_env` / `secret_env` feed the ConfigMap and ExternalSecret of the apps
# (docs/runbooks/infrastructure.md § Outputs to environment variables).

output "config_env" {
  description = "Non-secret environment variables (ConfigMap)."
  value       = module.northline.config_env
}

output "secret_env" {
  description = "Secret environment variables => secret reference in the secrets manager."
  value       = module.northline.secret_env
}

output "secrets_provider" {
  description = "External Secrets Operator provider."
  value       = module.northline.secrets_provider
}

output "secret_store" {
  description = "Secrets manager location used by this environment."
  value       = module.northline.secret_store
}

output "kubernetes" {
  description = "Cluster access and workload identities."
  value       = module.northline.kubernetes
}

output "registry" {
  description = "Container registry."
  value       = module.northline.registry
}

output "dns" {
  description = "DNS zone and the name servers to delegate to."
  value       = module.northline.dns
}

output "network" {
  description = "Network ids."
  value       = module.northline.network
}

output "kms" {
  description = "KMS keys."
  value       = module.northline.kms
}
