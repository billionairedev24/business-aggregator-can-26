provider "ovh" {
  endpoint = local.loc.api_endpoint
}

# ovh-mks — production-shaped on OVHcloud Public Cloud (docs/runbooks/ovh.md, DECISIONS 2026-10-19, S-160) in the
# location's region (ovh_location: US-EAST-VA-1 by default, US-WEST-OR-1 or BHS5): Managed
# Kubernetes on the free plan (control plane included, single zone), one node pool of 2 × b3-16 on a private network
# (vRack) with a gateway for the load balancer's public address, OVHcloud Managed PostgreSQL 17 on the private network
# (or Postgres in the cluster), Object Storage for uploads (and Postgres backups when Postgres is in the cluster).
# Valkey, Meilisearch and Kafka run in the cluster (deploy/helm/northline-data). Everything in one region.

locals {
  # The location (S-160): API endpoint, Public Cloud region, Object Storage, Managed Databases region —
  # infra/terraform/stacks/ovh/locations.json.
  loc    = jsondecode(file("${path.module}/../../../stacks/ovh/locations.json"))[var.ovh_location]
  region = local.loc.region
  # Managed PostgreSQL where the location offers it; elsewhere Postgres falls back to the cluster (CloudNativePG with
  # backups to Object Storage) — output postgres.fallback says so.
  pg_fallback = var.data_services.postgres.mode == "managed" && !local.loc.managed_postgres
  pg_mode     = local.pg_fallback ? "in-cluster" : var.data_services.postgres.mode
  managed     = local.pg_mode == "managed"
  # The stack sees a managed Postgres as an external one, at the database's private endpoint.
  pg_endpoint = local.managed ? one([for e in ovh_cloud_project_database.pg[0].endpoints : e if e.component == "postgresql"]) : null
  data_services = merge(var.data_services, {
    postgres = local.managed ? {
      mode    = "external"
      db_url  = "jdbc:postgresql://${local.pg_endpoint.domain}:${local.pg_endpoint.port}/northline?sslmode=require"
      db_user = var.data_services.postgres.db_user
    } : merge(var.data_services.postgres, { mode = local.pg_mode })
  })
  prefix_len = tonumber(split("/", var.network_cidr)[1])
  # The private network's regional OpenStack id: what the cluster, the gateway and the database nodes take.
  network_openstack_id = tolist(ovh_cloud_project_network_private.net.regions_attributes[*].openstackid)[0]
}

module "ovh" {
  source = "../../../stacks/ovh"

  service_name       = var.service_name
  location           = var.ovh_location
  target             = "ovh-mks"
  bucket_name_suffix = var.bucket_name_suffix
  storage_class_name = "csi-cinder-high-speed" # MKS's default block storage class
  data_services      = local.data_services
}

# ---- private network (vRack) --------------------------------------------------------------------------------------

resource "ovh_cloud_project_network_private" "net" {
  service_name = var.service_name
  name         = "northline-ovh-mks"
  vlan_id      = var.vlan_id
  regions      = [local.region]
}

resource "ovh_cloud_project_network_private_subnet" "net" {
  service_name = var.service_name
  network_id   = ovh_cloud_project_network_private.net.id
  region       = local.region
  network      = var.network_cidr
  start        = cidrhost(var.network_cidr, 10)
  end          = cidrhost(var.network_cidr, pow(2, 32 - local.prefix_len) - 10)
  dhcp         = true
  no_gateway   = false
}

# The subnet's router: the public load balancer of a cluster on a private network gets its public (floating) IP
# through it. Nodes keep their own public interface for egress (no routing through the gateway).
resource "ovh_cloud_project_gateway" "net" {
  service_name = var.service_name
  name         = "northline-ovh-mks"
  model        = "s"
  region       = local.region
  network_id   = local.network_openstack_id
  subnet_id    = ovh_cloud_project_network_private_subnet.net.id
}

# ---- Managed Kubernetes ---------------------------------------------------------------------------------------------

resource "ovh_cloud_project_kube" "cluster" {
  service_name       = var.service_name
  name               = "northline-ovh-mks"
  region             = local.region
  version            = var.kubernetes_version
  plan               = "free" # control plane included; "standard" is 3-AZ regions only
  update_policy      = "MINIMAL_DOWNTIME"
  private_network_id = local.network_openstack_id
  nodes_subnet_id    = ovh_cloud_project_network_private_subnet.net.id

  private_network_configuration {
    default_vrack_gateway              = ""
    private_network_routing_as_default = false
  }

  depends_on = [ovh_cloud_project_gateway.net]

  lifecycle {
    precondition {
      condition     = local.loc.managed_kubernetes
      error_message = "Managed Kubernetes is not offered in this location (locations.json): use the ovh-vps target there."
    }
  }
}

resource "ovh_cloud_project_kube_nodepool" "general" {
  service_name   = var.service_name
  kube_id        = ovh_cloud_project_kube.cluster.id
  name           = "general"
  flavor_name    = var.node_flavor
  desired_nodes  = var.node_count
  min_nodes      = 0
  max_nodes      = max(var.node_count, 2)
  autoscale      = false
  monthly_billed = var.monthly_billed
  anti_affinity  = false # anti-affinity groups cap a pool at 5 nodes and can't be changed later
}

resource "ovh_cloud_project_kube_iprestrictions" "api" {
  count        = length(var.api_allowed_cidrs) > 0 ? 1 : 0
  service_name = var.service_name
  kube_id      = ovh_cloud_project_kube.cluster.id
  ips          = var.api_allowed_cidrs
}

# ---- Managed PostgreSQL ---------------------------------------------------------------------------------------------
# PostgreSQL 17 (PostGIS is not enabled on 18 at OVHcloud), reachable only on the private network from the subnet.
# Extensions: there is no Terraform resource for them; scripts/ovh/deploy.sh creates postgis, citext and pgcrypto as
# avnadmin before the first migration (V001 then finds them). The app user owns nothing yet: the same script grants it
# CREATE on the database (Flyway creates the schemas).

resource "ovh_cloud_project_database" "pg" {
  count               = local.managed ? 1 : 0
  service_name        = var.service_name
  description         = "northline-ovh-mks"
  engine              = "postgresql"
  version             = var.managed_postgres.version
  plan                = var.managed_postgres.plan
  flavor              = var.managed_postgres.flavor
  disk_size           = var.managed_postgres.disk_size
  deletion_protection = false # demo data; set true before anything real goes in

  dynamic "nodes" {
    for_each = range(var.managed_postgres.plan == "business" ? 2 : 1)
    content {
      region     = module.ovh.database_region
      network_id = local.network_openstack_id
      subnet_id  = ovh_cloud_project_network_private_subnet.net.id
    }
  }

  ip_restrictions {
    description = "northline-ovh-mks private subnet"
    ip          = var.network_cidr
  }
}

resource "ovh_cloud_project_database_database" "northline" {
  count        = local.managed ? 1 : 0
  service_name = var.service_name
  engine       = "postgresql"
  cluster_id   = ovh_cloud_project_database.pg[0].id
  name         = "northline"
}

resource "ovh_cloud_project_database_postgresql_user" "app" {
  count        = local.managed ? 1 : 0
  service_name = var.service_name
  cluster_id   = ovh_cloud_project_database.pg[0].id
  name         = var.data_services.postgres.db_user
}

# The service's admin user (it exists already; naming it here resets its password into the state) — used once by
# scripts/ovh/deploy.sh for the extensions and the grant.
resource "ovh_cloud_project_database_postgresql_user" "admin" {
  count        = local.managed ? 1 : 0
  service_name = var.service_name
  cluster_id   = ovh_cloud_project_database.pg[0].id
  name         = "avnadmin"
}
