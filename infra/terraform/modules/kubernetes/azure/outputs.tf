output "cluster_name" {
  description = "AKS cluster name."
  value       = azurerm_kubernetes_cluster.this.name
}

output "cluster_endpoint" {
  description = "Kubernetes API URL."
  value       = "https://${azurerm_kubernetes_cluster.this.fqdn}:443"
}

output "cluster_ca_certificate" {
  description = "Cluster CA (base64 PEM)."
  value       = one(azurerm_kubernetes_cluster.this.kube_config[*].cluster_ca_certificate)
  sensitive   = true
}

output "oidc_issuer_url" {
  description = "Service-account token issuer (Workload Identity)."
  value       = azurerm_kubernetes_cluster.this.oidc_issuer_url
}

output "node_identity" {
  description = "Object id of the kubelet identity (image pulls)."
  value       = azurerm_user_assigned_identity.kubelet.principal_id
}

output "workload_identities" {
  description = "Per workload: the principal to grant (managed identity object id) and the annotations/labels for its Kubernetes ServiceAccount and pods (Helm, S-14)."
  value = {
    for k, v in var.workload_identities : k => {
      principal                   = azurerm_user_assigned_identity.workload[k].principal_id
      namespace                   = v.namespace
      service_account             = v.service_account
      service_account_annotations = { "azure.workload.identity/client-id" = azurerm_user_assigned_identity.workload[k].client_id }
      pod_labels                  = { "azure.workload.identity/use" = "true" }
    }
  }
}

output "kubeconfig_command" {
  description = "Command that writes a kubeconfig entry for this cluster (Entra ID sign-in via kubelogin)."
  value       = "az aks get-credentials --resource-group ${var.context.resource_group_name} --name ${azurerm_kubernetes_cluster.this.name} && kubelogin convert-kubeconfig -l azurecli"
}

output "cloud" {
  description = "Azure-only details."
  value = {
    cluster_id          = azurerm_kubernetes_cluster.this.id
    node_resource_group = azurerm_kubernetes_cluster.this.node_resource_group
    tenant_id           = data.azurerm_client_config.current.tenant_id
  }
}
