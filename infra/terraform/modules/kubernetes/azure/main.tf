# AKS: Azure CNI overlay with Cilium, OIDC issuer + Workload Identity, Azure RBAC for Kubernetes (local accounts off),
# etcd encryption with a Key Vault key (KMS plugin), outbound through the network's NAT gateway. The first node pool
# (by key order) is the system pool; the others are user pools. Each workload identity is a user-assigned managed
# identity with a federated credential for its Kubernetes service account.

data "azurerm_client_config" "current" {}

locals {
  rg = var.context.resource_group_name
  # The pool named "system" (or else the first by key order) is the AKS system pool.
  system_name = contains(keys(var.node_pools), "system") ? "system" : sort(keys(var.node_pools))[0]
  system      = var.node_pools[local.system_name]
  user_pools  = { for k, v in var.node_pools : k => v if k != local.system_name }
  zones       = var.context.environment == "dev" ? null : ["1", "2", "3"]

  # Key Vault ARM id from the key URL https://<vault>.vault.azure.net/keys/<name>/<version>.
  kms_vault_name = var.kms_key == null ? null : regex("^https://([^.]+)\\.", try(var.kms_key.id, null))[0]
  kms_vault_id   = var.kms_key == null ? null : "/subscriptions/${data.azurerm_client_config.current.subscription_id}/resourceGroups/${local.rg}/providers/Microsoft.KeyVault/vaults/${local.kms_vault_name}"
}

# Control-plane identity: must exist (and hold the key) before the cluster when KMS is on.
resource "azurerm_user_assigned_identity" "control_plane" {
  name                = "${var.context.name}-aks"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = var.context.tags
}

resource "azurerm_role_assignment" "control_plane_kms" {
  count                = var.kms_key == null ? 0 : 1
  scope                = local.kms_vault_id
  role_definition_name = "Key Vault Crypto User"
  principal_id         = azurerm_user_assigned_identity.control_plane.principal_id
}

# Kubelet identity (image pulls, node-level access): created here so it is stable across cluster rebuilds and can be
# granted AcrPull before the cluster exists. The control plane must be allowed to assign it to the nodes.
resource "azurerm_user_assigned_identity" "kubelet" {
  name                = "${var.context.name}-kubelet"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = var.context.tags
}

resource "azurerm_role_assignment" "control_plane_kubelet" {
  scope                = azurerm_user_assigned_identity.kubelet.id
  role_definition_name = "Managed Identity Operator"
  principal_id         = azurerm_user_assigned_identity.control_plane.principal_id
}

resource "azurerm_role_assignment" "control_plane_network" {
  scope                = var.network_id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.control_plane.principal_id
}

resource "azurerm_kubernetes_cluster" "this" {
  name                      = var.context.name
  location                  = var.context.region
  resource_group_name       = local.rg
  dns_prefix                = var.context.name
  kubernetes_version        = var.kubernetes_version
  sku_tier                  = var.context.environment == "prod" ? "Standard" : "Free"
  automatic_upgrade_channel = "patch"
  node_os_upgrade_channel   = "NodeImage"
  oidc_issuer_enabled       = true
  workload_identity_enabled = true
  local_account_disabled    = true
  azure_policy_enabled      = var.context.environment != "dev"
  tags                      = var.context.tags

  default_node_pool {
    name                         = local.system_name
    vm_size                      = local.system.machine_type
    vnet_subnet_id               = var.subnet_ids[0]
    auto_scaling_enabled         = true
    min_count                    = max(local.system.min_count, 1)
    max_count                    = local.system.max_count
    os_disk_size_gb              = local.system.disk_size_gb
    os_sku                       = "AzureLinux"
    zones                        = local.zones
    only_critical_addons_enabled = length(local.user_pools) > 0
    node_labels                  = merge(local.system.labels, { "northline.ca/pool" = local.system_name })
    temporary_name_for_rotation  = "tmp${substr(local.system_name, 0, 9)}"
    tags                         = var.context.tags

    upgrade_settings {
      max_surge = "33%"
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.control_plane.id]
  }

  kubelet_identity {
    client_id                 = azurerm_user_assigned_identity.kubelet.client_id
    object_id                 = azurerm_user_assigned_identity.kubelet.principal_id
    user_assigned_identity_id = azurerm_user_assigned_identity.kubelet.id
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    outbound_type       = "userAssignedNATGateway"
    load_balancer_sku   = "standard"
    pod_cidr            = "10.244.0.0/16"
    service_cidr        = "10.245.0.0/16"
    dns_service_ip      = "10.245.0.10"
  }

  # Manual = these node pools and the cluster autoscaler (Node Auto Provisioning off).
  node_provisioning_profile {
    mode = "Manual"
  }

  azure_active_directory_role_based_access_control {
    azure_rbac_enabled = true
    tenant_id          = data.azurerm_client_config.current.tenant_id
  }

  api_server_access_profile {
    authorized_ip_ranges = length(var.api_allowed_cidrs) > 0 ? var.api_allowed_cidrs : null
  }

  dynamic "key_management_service" {
    for_each = var.kms_key == null ? [] : [try(var.kms_key.id, null)]
    content {
      key_vault_key_id         = key_management_service.value
      key_vault_network_access = "Public"
    }
  }

  depends_on = [
    azurerm_role_assignment.control_plane_kms,
    azurerm_role_assignment.control_plane_network,
    azurerm_role_assignment.control_plane_kubelet,
  ]

  lifecycle {
    ignore_changes = [default_node_pool[0].node_count]
  }
}

resource "azurerm_kubernetes_cluster_node_pool" "this" {
  for_each              = local.user_pools
  name                  = each.key
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  vm_size               = each.value.machine_type
  vnet_subnet_id        = var.subnet_ids[0]
  auto_scaling_enabled  = true
  min_count             = each.value.min_count
  max_count             = each.value.max_count
  os_disk_size_gb       = each.value.disk_size_gb
  os_sku                = "AzureLinux"
  zones                 = local.zones
  priority              = each.value.spot ? "Spot" : "Regular"
  eviction_policy       = each.value.spot ? "Delete" : null
  spot_max_price        = each.value.spot ? -1 : null
  node_labels           = merge(each.value.labels, { "northline.ca/pool" = each.key })
  tags                  = var.context.tags

  lifecycle {
    ignore_changes = [node_count]
  }
}

resource "azurerm_role_assignment" "admin" {
  for_each             = toset(var.admin_principals)
  scope                = azurerm_kubernetes_cluster.this.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = each.value
}

# ---- Workload Identity -----------------------------------------------------------------------------------------

resource "azurerm_user_assigned_identity" "workload" {
  for_each            = var.workload_identities
  name                = "${var.context.name}-${each.key}"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = merge(var.context.tags, { service-account = "${each.value.namespace}.${each.value.service_account}" })
}

resource "azurerm_federated_identity_credential" "workload" {
  for_each                  = var.workload_identities
  name                      = "${each.value.namespace}-${each.value.service_account}"
  user_assigned_identity_id = azurerm_user_assigned_identity.workload[each.key].id
  issuer                    = azurerm_kubernetes_cluster.this.oidc_issuer_url
  subject                   = "system:serviceaccount:${each.value.namespace}:${each.value.service_account}"
  audience                  = ["api://AzureADTokenExchange"]
}

resource "azurerm_management_lock" "cluster" {
  count      = var.deletion_protection ? 1 : 0
  name       = "${var.context.name}-no-delete"
  scope      = azurerm_kubernetes_cluster.this.id
  lock_level = "CanNotDelete"
  notes      = "deletion_protection = true (Terraform)"
}
