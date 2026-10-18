# Offline plan with mocked providers: evaluates every expression, count/for_each and validation of this root
# without cloud credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "google" {
  # Well-formed values where the provider parses them at plan time (mocks return random strings).
  mock_resource "google_container_cluster" {
    override_during = plan
    defaults = {
      endpoint = "203.0.113.10"
    }
  }
  mock_data "google_project" {
    defaults = { number = "123456789012" }
  }
  mock_data "google_storage_project_service_account" {
    defaults = { email_address = "service-123456789012@gs-project-accounts.iam.gserviceaccount.com" }
  }
}

mock_provider "google-beta" {}

mock_provider "random" {}

mock_provider "ec" {}

variables {
  project_id = "northline-dryrun"
}

run "plan" {
  command = plan
}

run "in_cluster_by_default" {
  command = plan

  assert {
    condition     = output.config_env["DB_URL"] == "jdbc:postgresql://northline-pg-rw.northline-dryrun.svc.cluster.local:5432/northline?sslmode=require" && output.config_env["DB_USER"] == "northline_app"
    error_message = "In-cluster Postgres: DB_URL is the CloudNativePG read-write Service of deploy/helm/northline-data."
  }

  assert {
    condition     = output.config_env["REDIS_HOST"] == "northline-valkey.northline-dryrun.svc.cluster.local" && output.config_env["REDIS_SSL"] == "false"
    error_message = "In-cluster Valkey: REDIS_HOST is the chart's Service, no TLS."
  }

  assert {
    condition     = output.config_env["KAFKA_BOOTSTRAP"] == "northline-kafka.northline-dryrun.svc.cluster.local:9092" && output.config_env["KAFKA_SECURITY_PROTOCOL"] == "PLAINTEXT"
    error_message = "In-cluster Kafka: the single KRaft broker over PLAINTEXT."
  }

  assert {
    condition     = output.config_env["SEARCH_PROVIDER"] == "meilisearch" && output.config_env["MEILISEARCH_URL"] == "http://northline-meilisearch.northline-dryrun.svc.cluster.local:7700" && !contains(keys(output.config_env), "ES_URIS")
    error_message = "Search runs on Meilisearch (SEARCH_PROVIDER, MEILISEARCH_URL), not Elasticsearch."
  }

  assert {
    condition     = alltrue([for k in ["DB_PASSWORD", "REDIS_PASSWORD", "MEILISEARCH_API_KEY", "TOTP_KEY"] : contains(keys(output.secret_env), k)]) && !contains(keys(output.secret_env), "KAFKA_SASL_JAAS_CONFIG") && !contains(keys(output.secret_env), "ES_PASSWORD")
    error_message = "secret_env names the data services' credentials (and no Kafka/Elasticsearch secret for in-cluster services)."
  }

  assert {
    condition     = length(output.data_stores.data_services.secrets_to_fill) == 0 && output.data_values.postgres.backup.bucket == "northline-dryrun-pg-backups" && length(output.data_values.externalSecrets.remoteKeys) == 3
    error_message = "In-cluster services get generated credentials (nothing to fill by hand), and Postgres a backup bucket."
  }

  assert {
    condition     = output.data_stores.postgres == null && output.data_stores.cache == null && output.data_stores.kafka == null && output.data_stores.search == null
    error_message = "No managed data store (Cloud SQL, Memorystore, Managed Kafka, Elastic Cloud) in the dry run."
  }

  assert {
    condition     = length(output.network.cloud.nat_public_ips) == 0 && output.network.cloud.private_service_access == null && output.network.cloud.memorystore_psc_policy == null
    error_message = "No Cloud NAT, Private Service Access or PSC policy in the dry run (public nodes, no managed stores)."
  }

  assert {
    condition     = output.data_values.zone == "northamerica-northeast1-a" && output.data_values.postgres.enabled && output.data_values.postgres.backup.enabled && output.data_values.kafka.enabled
    error_message = "data_values: every service in the cluster's zone, Postgres with its backup."
  }

  assert {
    condition     = output.kubernetes.cluster_name == "northline-dryrun" && strcontains(output.kubernetes.kubeconfig_command, "--location northamerica-northeast1-a ")
    error_message = "One zonal cluster named after the environment, in <region>-a by default."
  }
}

run "zonal_cluster" {
  command = plan

  variables {
    zone = "northamerica-northeast1-b"
  }

  assert {
    condition     = strcontains(output.kubernetes.kubeconfig_command, "--location northamerica-northeast1-b ") && output.data_values.zone == "northamerica-northeast1-b"
    error_message = "The cluster is zonal, in var.zone."
  }
}

run "external_services" {
  command = plan

  variables {
    data_services = {
      cache  = { mode = "external", host = "valkey.example.net", port = 6380, ssl = true, username = "default" }
      search = { mode = "external", url = "https://search.example.net" }
      kafka  = { mode = "external", bootstrap = "kafka.example.net:9093", security_protocol = "SASL_SSL", sasl_mechanism = "PLAIN" }
    }
  }

  assert {
    condition     = output.config_env["REDIS_HOST"] == "valkey.example.net" && output.config_env["REDIS_PORT"] == "6380" && output.config_env["REDIS_SSL"] == "true" && output.config_env["REDIS_USERNAME"] == "default"
    error_message = "External Valkey: its endpoint in config_env."
  }

  assert {
    condition     = output.config_env["MEILISEARCH_URL"] == "https://search.example.net" && output.config_env["KAFKA_SECURITY_PROTOCOL"] == "SASL_SSL"
    error_message = "External Meilisearch and Kafka: their endpoints in config_env."
  }

  assert {
    condition     = contains(keys(output.secret_env), "KAFKA_SASL_JAAS_CONFIG") && length(output.data_stores.data_services.secrets_to_fill) == 3 && keys(output.data_values.externalSecrets.remoteKeys) == ["DB_PASSWORD"]
    error_message = "External services' credentials are created empty for the operator; only in-cluster Postgres gets a generated one."
  }

  assert {
    condition     = !output.data_values.valkey.enabled && !output.data_values.meilisearch.enabled && !output.data_values.kafka.enabled && output.data_values.postgres.enabled
    error_message = "deploy/helm/northline-data runs only the in-cluster services."
  }
}

run "external_needs_endpoint" {
  command = plan

  variables {
    data_services = { postgres = { mode = "external" } }
  }

  expect_failures = [var.data_services]
}

run "rejects_unknown_mode" {
  command = plan

  variables {
    data_services = { kafka = { mode = "managed" } }
  }

  expect_failures = [var.data_services]
}

run "rejects_region_outside_canada" {
  command = plan

  variables {
    region = "us-east4"
  }

  expect_failures = [var.region]
}

run "rejects_zone_outside_region" {
  command = plan

  variables {
    zone = "us-east4-a"
  }

  expect_failures = [var.zone]
}

run "edge" {
  command = plan

  assert {
    condition     = output.gitops_addon_values["external-dns"].txtOwnerId == "northline-dryrun" && contains(keys(output.helm_values.edge.certManager.issuer.dns01), "cloudDNS")
    error_message = "external-dns owns its records as northline-dryrun; the DNS-01 solver is Cloud DNS."
  }

  assert {
    condition     = output.helm_values.externalSecrets.enabled && output.helm_values.externalSecrets.provider == "gcp"
    error_message = "helm_values.externalSecrets: Secret Manager."
  }
}
