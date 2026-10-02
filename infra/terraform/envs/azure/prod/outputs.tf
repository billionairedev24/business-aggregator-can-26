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
    # S-17: the DNS-01 solver for this environment's zone (used when edge.certManager.issuer.solver = dns01).
    edge = { certManager = { issuer = { dns01 = module.northline.edge.cert_manager_dns01 } } }
  }
}

# S-17 (and S-6's External Secrets Operator): values for the platform add-ons Argo CD installs, one object per add-on
# (docs/runbooks/gitops.md, docs/runbooks/edge.md):
#   terraform output -json gitops_addon_values | jq '.["external-dns"]' | yq -P > deploy/argocd/envs/<env>/addons/external-dns.yaml
output "gitops_addon_values" {
  description = "Per add-on Helm values: the workload identity of external-secrets, external-dns and cert-manager, and external-dns's zone settings."
  value = {
    external-secrets = {
      serviceAccount = { annotations = module.northline.kubernetes.workload_identities["external-secrets"].service_account_annotations }
      podLabels      = module.northline.kubernetes.workload_identities["external-secrets"].pod_labels
    }
    external-dns = merge(module.northline.edge.external_dns, {
      serviceAccount = { annotations = module.northline.edge.identities["external-dns"].service_account_annotations }
      podLabels      = module.northline.edge.identities["external-dns"].pod_labels
      txtOwnerId     = "northline-prod"
    })
    cert-manager = {
      serviceAccount = { annotations = module.northline.edge.identities["cert-manager"].service_account_annotations }
      podLabels      = module.northline.edge.identities["cert-manager"].pod_labels
    }
  }
}

output "backup" {
  description = "S-114: backups, point-in-time recovery and the secondary-region copies per data store (docs/runbooks/backups-dr.md; read by scripts/dr/restore.sh)."
  value       = module.northline.backup
}
