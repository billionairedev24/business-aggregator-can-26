# Offline plan with a mocked OVHcloud provider: evaluates every expression, count/for_each and validation of this root
# without credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "ovh" {
  mock_resource "ovh_cloud_project_network_private" {
    defaults = { regions_attributes = [{ region = "BHS5", openstackid = "1f2e3d4c-0000-4000-8000-000000000001", status = "ACTIVE" }] }
  }
  mock_resource "ovh_cloud_project_database" {
    defaults = {
      endpoints = [
        { component = "postgresql", domain = "postgresql-0a1b2c3d-o4e5f6.database.cloud.ovh.net", port = 20184, scheme = "postgresql", ssl = true, ssl_mode = "require", path = "", uri = "" },
        { component = "postgresqlRead", domain = "replica-postgresql-0a1b2c3d-o4e5f6.database.cloud.ovh.net", port = 20184, scheme = "postgresql", ssl = true, ssl_mode = "require", path = "", uri = "" },
      ]
    }
  }
}

variables {
  service_name = "0123456789abcdef0123456789abcdef"
}

run "managed_postgres_by_default" {
  command = apply # mocks: the database endpoint becomes known

  assert {
    condition     = output.config_env["DB_URL"] == "jdbc:postgresql://postgresql-0a1b2c3d-o4e5f6.database.cloud.ovh.net:20184/northline?sslmode=require" && output.config_env["DB_USER"] == "northline_app"
    error_message = "Managed Postgres: DB_URL is the database's private endpoint, sslmode=require."
  }

  assert {
    condition     = !output.data_values.postgres.enabled && output.data_values.valkey.enabled && output.data_values.meilisearch.enabled && output.data_values.kafka.enabled && length(output.storage.buckets) == 1
    error_message = "Managed Postgres stays out of northline-data (no backup bucket: OVHcloud backs it up); Valkey, Meilisearch, Kafka in the cluster."
  }

  assert {
    condition     = ovh_cloud_project_database.pg[0].version == "17" && ovh_cloud_project_database.pg[0].plan == "essential" && length(ovh_cloud_project_database.pg[0].nodes) == 1 && ovh_cloud_project_database.pg[0].nodes[0].region == "BHS"
    error_message = "PostgreSQL 17 (PostGIS), Essential, one node in BHS."
  }

  assert {
    condition     = ovh_cloud_project_database.pg[0].nodes[0].network_id == "1f2e3d4c-0000-4000-8000-000000000001" && one(ovh_cloud_project_database.pg[0].ip_restrictions).ip == "10.60.0.0/24"
    error_message = "The database sits on the private network, reachable from the subnet only."
  }

  assert {
    condition     = ovh_cloud_project_kube.cluster.region == "BHS5" && ovh_cloud_project_kube.cluster.plan == "free" && ovh_cloud_project_kube_nodepool.general.desired_nodes == 2 && ovh_cloud_project_kube_nodepool.general.flavor_name == "b3-16" && !ovh_cloud_project_kube_nodepool.general.monthly_billed
    error_message = "Free MKS control plane in BHS5, 2 × b3-16 billed hourly."
  }

  assert {
    condition     = ovh_cloud_project_network_private_subnet.net.start == "10.60.0.10" && ovh_cloud_project_network_private_subnet.net.end == "10.60.0.246" && length(ovh_cloud_project_kube_iprestrictions.api) == 0
    error_message = "Subnet DHCP range inside network_cidr; no API restriction without api_allowed_cidrs."
  }

  assert {
    condition     = output.data_values.storageClassName == "csi-cinder-high-speed" && output.config_env["STORAGE_ENDPOINT"] == "https://s3.bhs.io.cloud.ovh.net" && output.config_env["KMS_PROVIDER"] == "local"
    error_message = "Cinder volumes, Object Storage in BHS, local keys."
  }
}

run "business_plan_two_nodes" {
  command = plan

  variables {
    managed_postgres = { plan = "business" }
  }

  assert {
    condition     = length(ovh_cloud_project_database.pg[0].nodes) == 2
    error_message = "Business is two nodes (HA)."
  }
}

run "in_cluster_postgres" {
  command = plan

  variables {
    data_services     = { postgres = { mode = "in-cluster" } }
    api_allowed_cidrs = ["198.51.100.7/32"]
  }

  assert {
    condition     = length(ovh_cloud_project_database.pg) == 0 && output.data_values.postgres.enabled && output.data_values.postgres.backup.bucket == "northline-ovh-mks-pg-backups" && output.config_env["DB_URL"] == "jdbc:postgresql://northline-pg-rw.northline-dryrun.svc.cluster.local:5432/northline?sslmode=require"
    error_message = "In-cluster Postgres: no managed database, CloudNativePG with S3 backups."
  }

  assert {
    condition     = length(ovh_cloud_project_kube_iprestrictions.api) == 1
    error_message = "api_allowed_cidrs restricts the Kubernetes API."
  }
}

run "scaled_down" {
  command = plan

  variables {
    node_count = 0
  }

  assert {
    condition     = ovh_cloud_project_kube_nodepool.general.desired_nodes == 0 && ovh_cloud_project_kube_nodepool.general.min_nodes == 0
    error_message = "make ovh-down: the pool at 0 nodes."
  }
}

run "postgres_18_refused" {
  command = plan

  variables {
    managed_postgres = { version = "18" }
  }

  expect_failures = [var.managed_postgres]
}

run "canadian_region_only" {
  command = plan

  variables {
    region = "DE1"
  }

  expect_failures = [var.region]
}
