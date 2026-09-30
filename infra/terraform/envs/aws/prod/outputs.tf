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

output "data_stores" {
  description = "Data store details for operators (admin secrets, Kafka topic provisioning)."
  value       = module.northline.data_stores
}

# S-6: everything the Helm chart (deploy/helm/northline) needs from this environment, as a values file:
#   terraform output -json helm_values > northline-values.json   →   helm upgrade … -f northline-values.json
# Holds no secret: configEnv is non-secret by construction, externalSecrets.remoteKeys only names secrets.
output "helm_values" {
  description = "Values for deploy/helm/northline: configEnv, workloadIdentities, externalSecrets (docs/runbooks/deploy.md)."
  value = {
    configEnv = module.northline.config_env
    workloadIdentities = {
      for name, wi in module.northline.kubernetes.workload_identities : name => {
        service_account_annotations = wi.service_account_annotations
        pod_labels                  = wi.pod_labels
      } if name != "external-secrets"
    }
    externalSecrets = merge(module.northline.external_secrets, {
      enabled    = true
      remoteKeys = { for name, ref in module.northline.secret_env : name => ref if ref != null && ref != "" }
    })
  }
}
