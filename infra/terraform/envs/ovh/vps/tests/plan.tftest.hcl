# Offline plan with a mocked OVHcloud provider: evaluates every expression, count/for_each and validation of this root
# without credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "ovh" {
  mock_data "ovh_vps" {
    defaults = { ips = ["203.0.113.20", "2001:db8::20"] }
  }
}

variables {
  service_name = "0123456789abcdef0123456789abcdef"
}

run "storage_only_before_the_vps_is_ordered" {
  command = plan

  assert {
    condition     = output.vps.ipv4 == null && length(ovh_ip_firewall.box) == 0 && length(output.dns_records) == 0
    error_message = "Without vps_service_name: Object Storage only, no firewall, no records."
  }

  assert {
    condition     = output.config_env["STORAGE_PROVIDER"] == "s3" && output.config_env["STORAGE_ENDPOINT"] == "https://s3.bhs.io.cloud.ovh.net" && output.config_env["STORAGE_REGION"] == "bhs" && output.config_env["STORAGE_PATH_STYLE"] == "true" && output.config_env["STORAGE_ENCRYPTION_KEY"] == ""
    error_message = "Uploads on OVHcloud Object Storage in BHS over the S3 API."
  }

  assert {
    condition     = output.config_env["KMS_PROVIDER"] == "local" && output.config_env["SEARCH_PROVIDER"] == "meilisearch"
    error_message = "No key service on OVHcloud (local keys, dev profile); search on Meilisearch."
  }

  assert {
    condition     = output.config_env["DB_URL"] == "jdbc:postgresql://northline-pg-rw.northline-dryrun.svc.cluster.local:5432/northline?sslmode=require" && output.config_env["REDIS_HOST"] == "northline-valkey.northline-dryrun.svc.cluster.local" && output.config_env["KAFKA_BOOTSTRAP"] == "northline-kafka.northline-dryrun.svc.cluster.local:9092"
    error_message = "Every data service on the VPS by default (deploy/helm/northline-data Services)."
  }

  assert {
    condition     = length(module.ovh.storage.buckets) == 2 && output.data_values.postgres.backup.provider == "s3" && output.data_values.postgres.backup.bucket == "northline-ovh-vps-pg-backups" && !output.data_values.pinToZone && output.data_values.storageClassName == "local-path" && output.data_values.externalSecrets.provider == "none" && output.data_values.authSigningKeys.enabled
    error_message = "data_values: S3 backups to their own bucket, no zone pinning, local-path volumes, secrets from the script, the signing-key volume."
  }
}

run "firewall_in_front_of_the_vps" {
  command = plan

  variables {
    vps_service_name = "vps-1a2b3c4d.vps.ovh.ca"
    admin_cidrs      = ["198.51.100.7/32"]
  }

  assert {
    condition     = output.vps.ipv4 == "203.0.113.20" && output.dns_records[1] == "*.demo.northline.ca. 300 IN A 203.0.113.20"
    error_message = "The VPS's IPv4 is the target of every public host."
  }

  assert {
    condition = (
      length(ovh_ip_firewall_rule.box) == 8 &&
      local.firewall_rules["1-ssh-198.51.100.7/32"].destination_port == 22 && local.firewall_rules["1-ssh-198.51.100.7/32"].source == "198.51.100.7/32" &&
      local.firewall_rules["19-deny-rest"].action == "deny" && local.firewall_rules["19-deny-rest"].sequence == 19 &&
      alltrue([for k, r in local.firewall_rules : r.action == "deny" || r.protocol != "tcp" || r.tcp_option == "established" || contains([22, 80, 443], r.destination_port)])
    )
    error_message = "Network Firewall: only SSH from admin_cidrs, 80 and 443 (plus replies), everything else denied."
  }
}

run "firewall_needs_an_admin_cidr" {
  command = plan

  variables {
    vps_service_name = "vps-1a2b3c4d.vps.ovh.ca"
  }

  expect_failures = [ovh_ip_firewall.box]
}

run "external_search" {
  command = plan

  variables {
    data_services = { search = { mode = "external", url = "https://search.example" } }
  }

  assert {
    condition     = output.config_env["MEILISEARCH_URL"] == "https://search.example" && !output.data_values.meilisearch.enabled && output.data_values.postgres.enabled
    error_message = "An external Meilisearch is left out of northline-data and named in configEnv."
  }
}

run "canadian_region_only" {
  command = plan

  variables {
    region = "GRA11"
  }

  expect_failures = [var.region]
}
