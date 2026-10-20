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
    condition     = output.config_env["STORAGE_PROVIDER"] == "s3" && output.config_env["STORAGE_ENDPOINT"] == "https://s3.us-east-va.io.cloud.ovh.us" && output.config_env["STORAGE_REGION"] == "us-east-va" && output.config_env["STORAGE_PATH_STYLE"] == "true" && output.config_env["STORAGE_ENCRYPTION_KEY"] == ""
    error_message = "Default location us-east-va: uploads on OVHcloud US Object Storage in Vint Hill over the S3 API."
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
    vps_service_name = "vps-1a2b3c4d.vps.ovh.us"
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

run "pipeline_runner_ssh_for_one_run" {
  command = plan

  variables {
    vps_service_name = "vps-1a2b3c4d.vps.ovh.us"
    admin_cidrs      = ["198.51.100.7/32"]
    ci_ssh_cidr      = "192.0.2.44/32"
  }

  assert {
    condition     = length(ovh_ip_firewall_rule.box) == 9 && local.firewall_rules["9-ssh-ci"].source == "192.0.2.44/32" && local.firewall_rules["9-ssh-ci"].destination_port == 22 && local.firewall_rules["9-ssh-ci"].sequence < local.firewall_rules["19-deny-rest"].sequence
    error_message = "ci_ssh_cidr adds one SSH rule before the final deny."
  }
}

run "firewall_needs_an_admin_cidr" {
  command = plan

  variables {
    vps_service_name = "vps-1a2b3c4d.vps.ovh.us"
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

# S-160: one run per location — everything derived from ovh_location (locations.json).
run "location_us_east_va" {
  command = plan

  variables {
    ovh_location     = "us-east-va"
    vps_service_name = "vps-1a2b3c4d.vps.ovh.us"
    admin_cidrs      = ["198.51.100.7/32"]
  }

  assert {
    condition = (
      output.location.api_endpoint == "ovh-us" && output.location.country == "US" && output.location.token_url == "https://api.us.ovhcloud.com/createToken/" &&
      output.location.region == "US-EAST-VA-1" && output.location.vps_datacenter == "Vint Hill (US-EAST-VA)" &&
      module.ovh.storage.region == "US-EAST-VA" && output.config_env["STORAGE_ENDPOINT"] == "https://s3.us-east-va.io.cloud.ovh.us" &&
      output.data_values.postgres.backup.s3.endpointURL == "https://s3.us-east-va.io.cloud.ovh.us" && output.helm_values.global.hostingCountry == "US"
    )
    error_message = "us-east-va: OVHcloud US API, Vint Hill, Object Storage US-EAST-VA, hosting country US for the chart's residency guard."
  }

  assert {
    condition     = output.location.edge_firewall_used && length(ovh_ip_firewall_rule.box) == 8
    error_message = "The Network Firewall in front of the VPS in Vint Hill."
  }
}

run "location_us_west_or" {
  command = plan

  variables {
    ovh_location = "us-west-or"
  }

  assert {
    condition = (
      output.location.api_endpoint == "ovh-us" && output.location.region == "US-WEST-OR-1" && module.ovh.storage.region == "US-WEST-OR" &&
      output.config_env["STORAGE_ENDPOINT"] == "https://s3.us-west-or.io.cloud.ovh.us" && output.config_env["STORAGE_REGION"] == "us-west-or" &&
      output.s3_credentials["uploads"].region == "us-west-or" && output.helm_values.global.hostingCountry == "US"
    )
    error_message = "us-west-or: OVHcloud US API, Hillsboro, Object Storage US-WEST-OR."
  }
}

run "location_ca_bhs" {
  command = plan

  variables {
    ovh_location = "ca-bhs"
  }

  assert {
    condition = (
      output.location.api_endpoint == "ovh-ca" && output.location.country == "CA" && output.location.token_url == "https://ca.api.ovh.com/createToken/" &&
      output.location.region == "BHS5" && module.ovh.storage.region == "BHS" && output.config_env["STORAGE_ENDPOINT"] == "https://s3.bhs.io.cloud.ovh.net" &&
      output.config_env["STORAGE_REGION"] == "bhs" && output.helm_values.global.hostingCountry == "CA"
    )
    error_message = "ca-bhs: OVHcloud Canada API, Beauharnois, Object Storage BHS — Canadian data residency."
  }
}

run "edge_firewall_off" {
  command = plan

  variables {
    vps_service_name = "vps-1a2b3c4d.vps.ovh.us"
    edge_firewall    = false
  }

  assert {
    condition     = length(ovh_ip_firewall.box) == 0 && !output.vps.edge_firewall && !output.location.edge_firewall_used
    error_message = "edge_firewall = false (or a location without the Network Firewall): no firewall resources, ufw on the host only."
  }
}

run "unknown_location_refused" {
  command = plan

  variables {
    ovh_location = "gra"
  }

  expect_failures = [var.ovh_location]
}
