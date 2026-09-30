# Env roots re-export these. config_env and secret_env are keyed by the application's environment variable names
# (docs/runbooks/README.md § Variables at a glance), so they can be written straight into a ConfigMap and the
# ExternalSecret mapping.

output "config_env" {
  description = "Non-secret environment variables for the ConfigMap."
  value = {
    # api, object storage (S-10, docs/runbooks/object-storage.md). Workload identity: STORAGE_ACCESS_KEY/SECRET_KEY
    # stay unset, STORAGE_PATH_STYLE false.
    STORAGE_PROVIDER       = module.storage.storage_provider
    STORAGE_BUCKET         = module.storage.bucket_names["uploads"]
    STORAGE_REGION         = module.storage.storage_region
    STORAGE_ENDPOINT       = module.storage.storage_endpoint
    STORAGE_ENCRYPTION_KEY = module.storage.storage_encryption_key

    # northline-auth, token signing (S-7, docs/runbooks/key-rotation.md). var.signing_key_ids overrides the key
    # Terraform created during a rotation.
    KMS_PROVIDER          = module.kms.kms_provider
    KMS_KEY_ID            = coalesce(var.signing_key_ids.active, module.kms.key_refs["signing"])
    KMS_PUBLISHED_KEY_IDS = join(",", var.signing_key_ids.published)

    DB_URL                  = module.postgres.db_url
    DB_USER                 = module.postgres.db_user
    REDIS_HOST              = module.cache.redis_host
    REDIS_PORT              = tostring(module.cache.redis_port)
    REDIS_SSL               = tostring(module.cache.redis_ssl)
    KAFKA_BOOTSTRAP         = module.kafka.kafka_bootstrap
    KAFKA_SECURITY_PROTOCOL = module.kafka.kafka_security_protocol
    KAFKA_SASL_MECHANISM    = module.kafka.kafka_sasl_mechanism
    ES_URIS                 = module.search.es_uris
    ES_USERNAME             = module.search.es_username
  }
}

output "secret_env" {
  description = "Secret environment variables => secret reference in the cloud secrets manager (ExternalSecret remoteRef.key)."
  value = merge(
    { for var_name, secret in local.app_secrets : var_name => module.secrets.secret_refs[secret] },
    {
      DB_PASSWORD            = module.postgres.db_password_secret_ref
      KAFKA_SASL_JAAS_CONFIG = module.kafka.kafka_sasl_jaas_config_secret_ref
      ES_PASSWORD            = module.search.es_password_secret_ref
      REDIS_PASSWORD         = module.cache.redis_password_secret_ref
    },
  )
}

output "secrets_provider" {
  description = "External Secrets Operator provider (SecretStore spec.provider)."
  value       = module.secrets.secrets_provider
}

output "external_secrets" {
  description = "External Secrets Operator store settings for the Helm chart (externalSecrets.provider and its block, S-6)."
  value = {
    provider = "azure"
    azure    = { vaultUrl = module.secrets.cloud.key_vault_uri }
  }
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

output "data_stores" {
  description = "Data store details for operators: admin secrets, topic provisioning, TLS notes."
  value = {
    postgres = {
      host             = module.postgres.db_host
      database         = module.postgres.db_name
      app_user         = module.postgres.db_user
      admin_secret_ref = module.postgres.admin_secret_ref
      cloud            = module.postgres.cloud
    }
    cache = {
      host  = module.cache.redis_host
      port  = module.cache.redis_port
      cloud = module.cache.cloud
    }
    kafka = {
      bootstrap          = module.kafka.kafka_bootstrap
      replication_factor = module.kafka.kafka_replication_factor
      topic_policy       = module.kafka.kafka_topic_policy
      cloud              = module.kafka.cloud
    }
    search = {
      es_uris = module.search.es_uris
      cloud   = module.search.cloud
    }
  }
}
