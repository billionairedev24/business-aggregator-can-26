# scripts/ovh/deploy.sh reads helm_values, data_values and postgres; scripts/ovh/secrets.sh reads s3_credentials and
# postgres_credentials; scripts/ovh/scale.sh reads kubernetes.

output "config_env" {
  description = "Non-secret environment variables (the chart's configEnv)."
  value       = module.ovh.config_env
}

output "helm_values" {
  description = "Values for deploy/helm/northline: configEnv (layered after values-dryrun.yaml and values-ovh-mks.yaml)."
  value       = { configEnv = module.ovh.config_env }
}

output "data_values" {
  description = "Values for deploy/helm/northline-data (terraform output -json data_values > ovh-mks-data-values.json)."
  value       = module.ovh.data_values
}

output "storage" {
  description = "Object Storage buckets, S3 endpoint and region, access key ids."
  value       = module.ovh.storage
}

output "s3_credentials" {
  description = "S3 keys of the uploads user (and the Postgres-backup user with in-cluster Postgres); read by scripts/ovh/secrets.sh."
  sensitive   = true
  value       = module.ovh.s3_credentials
}

output "kubernetes" {
  description = "The cluster: id, version, node pool size, and how to get a kubeconfig."
  value = {
    cluster_id         = ovh_cloud_project_kube.cluster.id
    version            = ovh_cloud_project_kube.cluster.version
    node_flavor        = var.node_flavor
    node_count         = var.node_count
    kubeconfig_command = "terraform -chdir=infra/terraform/envs/ovh/mks output -raw kubeconfig > ~/.kube/northline-ovh-mks && chmod 600 ~/.kube/northline-ovh-mks && export KUBECONFIG=~/.kube/northline-ovh-mks"
  }
}

output "kubeconfig" {
  description = "Admin kubeconfig of the cluster (terraform output -raw kubeconfig)."
  sensitive   = true
  value       = ovh_cloud_project_kube.cluster.kubeconfig
}

output "postgres" {
  description = "Postgres placement; for the managed database its private endpoint and plan."
  value = {
    mode   = local.pg_mode
    host   = local.managed ? local.pg_endpoint.domain : null
    port   = local.managed ? local.pg_endpoint.port : null
    plan   = local.managed ? var.managed_postgres.plan : null
    flavor = local.managed ? var.managed_postgres.flavor : null
  }
}

output "postgres_credentials" {
  description = "Managed Postgres: the app user's password (DB_PASSWORD) and the admin URI used once for the extensions. Read by scripts/ovh/secrets.sh and deploy.sh — never printed."
  sensitive   = true
  value = local.managed ? {
    app_password = ovh_cloud_project_database_postgresql_user.app[0].password
    admin_uri    = "postgresql://avnadmin:${urlencode(ovh_cloud_project_database_postgresql_user.admin[0].password)}@${local.pg_endpoint.domain}:${local.pg_endpoint.port}/northline?sslmode=require"
  } : null
}

output "network" {
  description = "Private network, subnet and gateway ids."
  value = {
    network_id = ovh_cloud_project_network_private.net.id
    subnet_id  = ovh_cloud_project_network_private_subnet.net.id
    gateway_id = ovh_cloud_project_gateway.net.id
    cidr       = var.network_cidr
  }
}

output "dns_records" {
  description = "Records to create once the load balancer has its address (kubectl -n envoy-gateway-system get svc): every public host → that IP."
  value       = [for h in [var.dns_zone_name, "*.${var.dns_zone_name}"] : "${h}. 300 IN A <load balancer IP>"]
}

output "in_cluster" {
  description = "Which data services run in the cluster."
  value       = module.ovh.in_cluster
}
