# Env roots re-export these. config_env and secret_env are keyed by the application's environment variable names
# (docs/runbooks/README.md § Variables at a glance), so they can be written straight into a ConfigMap and the
# ExternalSecret mapping.

output "config_env" {
  description = "Non-secret environment variables for the ConfigMap."
  value = {
    STORAGE_PROVIDER = module.storage.storage_provider
    STORAGE_BUCKET   = module.storage.bucket_names["uploads"]
    STORAGE_REGION   = module.storage.storage_region
    STORAGE_ENDPOINT = module.storage.storage_endpoint
    KMS_PROVIDER     = module.kms.kms_provider
    KMS_KEY_ID       = module.kms.key_refs["signing"]
  }
}

output "secret_env" {
  description = "Secret environment variables => secret reference in the cloud secrets manager (ExternalSecret remoteRef.key)."
  value       = { for var_name, secret in local.app_secrets : var_name => module.secrets.secret_refs[secret] }
}

output "secrets_provider" {
  description = "External Secrets Operator provider (SecretStore spec.provider)."
  value       = module.secrets.secrets_provider
}

output "secret_store" {
  description = "Where generated secrets are written."
  value       = module.secrets.store
}

output "network" {
  description = "Network ids."
  value = {
    network_id         = module.network.network_id
    cidr               = module.network.cidr
    cluster_subnet_ids = module.network.cluster_subnet_ids
    data_subnet_ids    = module.network.data_subnet_ids
    cloud              = module.network.cloud
  }
}

output "kubernetes" {
  description = "Cluster access and workload identities (ServiceAccount annotations for Helm, S-14)."
  value = {
    cluster_name        = module.kubernetes.cluster_name
    cluster_endpoint    = module.kubernetes.cluster_endpoint
    oidc_issuer_url     = module.kubernetes.oidc_issuer_url
    kubeconfig_command  = module.kubernetes.kubeconfig_command
    workload_identities = module.kubernetes.workload_identities
  }
}

output "registry" {
  description = "Container registry."
  value = {
    registry_url    = module.registry.registry_url
    repository_urls = module.registry.repository_urls
    login_command   = module.registry.cloud.login_command
  }
}

output "dns" {
  description = "DNS zone; create the name_servers as NS records in the parent zone."
  value = {
    zone_name    = module.dns.zone_name
    name_servers = module.dns.name_servers
  }
}

output "kms" {
  description = "KMS keys."
  value = {
    key_ids  = module.kms.key_ids
    key_refs = module.kms.key_refs
  }
}
