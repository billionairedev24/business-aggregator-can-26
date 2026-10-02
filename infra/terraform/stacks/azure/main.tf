# Northline on Azure: composes the capability modules for one environment. Env roots (envs/azure/<env>) only choose
# sizes; everything else is identical across environments.

locals {
  name      = "northline-${var.environment}"
  namespace = "northline-${var.environment}"

  tags = {
    app              = "northline"
    env              = var.environment
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }

  context = {
    resource_group_name = azurerm_resource_group.this.name
    name                = local.name
    environment         = var.environment
    region              = var.region
    tags                = local.tags
  }

  # Kubernetes service accounts that get a cloud identity (Helm charts, S-14, use these names).
  workload_identities = {
    api              = { namespace = local.namespace, service_account = "northline-api" }
    auth             = { namespace = local.namespace, service_account = "northline-auth" }
    bff              = { namespace = local.namespace, service_account = "northline-bff" }
    worker           = { namespace = local.namespace, service_account = "northline-worker" }
    external-secrets = { namespace = "external-secrets", service_account = "external-secrets" }
    # S-17 edge add-ons: DNS records for the public hosts, and DNS-01 challenges.
    external-dns = { namespace = "external-dns", service_account = "external-dns" }
    cert-manager = { namespace = "cert-manager", service_account = "cert-manager" }
    # S-111: the OpenTelemetry Collector (chart: observability.collector) writes traces, metrics and logs to the
    # cloud's own backend with this identity (docs/runbooks/observability.md).
    otel-collector = { namespace = local.namespace, service_account = "northline-otel-collector" }
  }

  # Application secrets created empty; an operator sets the values (docs/runbooks/<env>.md § Environment variables).
  app_secrets = {
    TOTP_KEY                 = "totp-key"
    WEBHOOK_SECRET_KEY       = "webhook-secret-key"
    STUDIO_BFF_SECRET        = "studio-bff-secret"
    STUDIO_BFF_SECRET_HASH   = "studio-bff-secret-hash"
    CONSUMER_BFF_SECRET      = "consumer-bff-secret"
    CONSUMER_BFF_SECRET_HASH = "consumer-bff-secret-hash"
    CONSOLE_BFF_SECRET       = "console-bff-secret"
    CONSOLE_BFF_SECRET_HASH  = "console-bff-secret-hash"
    STRIPE_SECRET_KEY        = "stripe-secret-key"
    STRIPE_PUBLISHABLE_KEY   = "stripe-publishable-key"
    GOOGLE_CLIENT_SECRET     = "google-client-secret"
    APPLE_PRIVATE_KEY        = "apple-private-key"
    SMS_AUTH_TOKEN           = "sms-auth-token"
    # S-102 push (worker): the APNs .p8 signing key (PEM) and the Firebase service account JSON key.
    PUSH_APNS_KEY            = "push-apns-key"
    PUSH_FCM_SERVICE_ACCOUNT = "push-fcm-service-account"
    # S-12 Stripe webhook signing secrets, S-13 email (unsubscribe HMAC key, SendGrid/Azure key, SMTP relay password).
    STRIPE_WEBHOOK_SECRET         = "stripe-webhook-secret"
    STRIPE_CONNECT_WEBHOOK_SECRET = "stripe-connect-webhook-secret"
    EMAIL_UNSUBSCRIBE_KEY         = "email-unsubscribe-key"
    EMAIL_API_KEY                 = "email-api-key"
    SMTP_PASSWORD                 = "smtp-password"
    # S-23 business registries: ISED API Store key, OpenCorporates token, Socrata app token (optional).
    REGISTRY_CORPORATIONS_CANADA_KEY = "registry-corporations-canada-key"
    REGISTRY_ALBERTA_KEY             = "registry-alberta-key"
    REGISTRY_CALGARY_APP_TOKEN       = "registry-calgary-app-token"
    # S-32 calendar sync: the Google OAuth client and Microsoft Entra app registration secrets.
    GOOGLE_CALENDAR_CLIENT_SECRET    = "google-calendar-client-secret"
    MICROSOFT_CALENDAR_CLIENT_SECRET = "microsoft-calendar-client-secret"
    # S-35 catalogue sync: the Shopify / Square / Lightspeed app secrets and the Square webhook signature key.
    SHOPIFY_CLIENT_SECRET        = "shopify-client-secret"
    SQUARE_CLIENT_SECRET         = "square-client-secret"
    SQUARE_WEBHOOK_SIGNATURE_KEY = "square-webhook-signature-key"
    LIGHTSPEED_CLIENT_SECRET     = "lightspeed-client-secret"
    # S-36 POS menu import: the Clover app secret and Toast partner credentials (Square reuses S-35's app).
    CLOVER_CLIENT_SECRET = "clover-client-secret"
    TOAST_CLIENT_SECRET  = "toast-client-secret"
    # S-47 addresses: the server-side Google Maps Platform key (Places API (New) + Geocoding API).
    GOOGLE_MAPS_API_KEY = "google-maps-api-key"
    # S-111: an OTLP backend's credentials (e.g. Grafana Cloud "Basic <base64 instance:token>"); empty = the cloud's own.
    OTEL_BACKEND_AUTH = "otel-backend-auth"
    # S-111: Azure Monitor (Application Insights) connection string for the Collector's azuremonitor exporter.
    APPLICATIONINSIGHTS_CONNECTION_STRING = "applicationinsights-connection-string"
    # S-129 AI platform: the OpenRouter API key (empty until created; AI features answer 503 without it).
    OPENROUTER_API_KEY = "openrouter-api-key"
    # S-91 console overview health: a metrics store's read token (empty unless the store needs one).
    CONSOLE_HEALTH_PROMETHEUS_TOKEN = "console-health-prometheus-token"
    # S-113 alerting and on-call: the on-call rota export token (api) and the alert receivers' keys (Alertmanager);
    # empty until the paging tool is chosen (docs/runbooks/alerting.md).
    ONCALL_EXPORT_TOKEN            = "oncall-export-token"
    ALERTING_PAGERDUTY_ROUTING_KEY = "alerting-pagerduty-routing-key"
    ALERTING_OPSGENIE_API_KEY      = "alerting-opsgenie-api-key"
    ALERTING_PAGE_WEBHOOK_URL      = "alerting-page-webhook-url"
    ALERTING_TICKET_WEBHOOK_URL    = "alerting-ticket-webhook-url"
  }
}

resource "azurerm_resource_group" "this" {
  name     = "rg-${local.name}"
  location = var.region
  tags     = local.tags
}

module "network" {
  source                = "../../modules/network/azure"
  context               = local.context
  cidr                  = var.cidr
  zone_count            = var.zone_count
  high_availability_nat = var.high_availability_nat
}

module "kms" {
  source  = "../../modules/kms/azure"
  context = local.context
  keys = {
    data    = { usage = "encrypt" }
    signing = { usage = "sign" }
    # S-32: the api's envelope key for secrets it stores (calendar refresh tokens); KMS_ENCRYPTION_KEY_ID.
    tokens = { usage = "encrypt" }
  }
  key_users = {
    # Only northline-auth signs tokens (S-7); the api and bff verify through the JWK set, not the KMS.
    signing = {
      auth = module.kubernetes.workload_identities["auth"].principal
    }
    # Only the api seals and opens its stored secrets (S-32).
    tokens = {
      api = module.kubernetes.workload_identities["api"].principal
    }
  }
  deletion_protection = var.deletion_protection
}

module "kubernetes" {
  source              = "../../modules/kubernetes/azure"
  context             = local.context
  network_id          = module.network.network_id
  subnet_ids          = module.network.cluster_subnet_ids
  kubernetes_version  = var.kubernetes_version
  node_pools          = var.node_pools
  kms_key             = { id = module.kms.key_ids["data"] }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  workload_identities = local.workload_identities
  deletion_protection = var.deletion_protection
}

module "registry" {
  source  = "../../modules/registry/azure"
  context = local.context
  kms_key = { id = module.kms.key_ids["data"] }
  readers = { nodes = module.kubernetes.node_identity }
}

module "dns" {
  source    = "../../modules/dns/azure"
  context   = local.context
  zone_name = var.dns_zone_name
  # S-17: only these two identities may write records in the zone.
  record_writers = {
    external-dns = module.kubernetes.workload_identities["external-dns"].principal
    cert-manager = module.kubernetes.workload_identities["cert-manager"].principal
  }
}

module "storage" {
  source        = "../../modules/storage/azure"
  context       = local.context
  buckets       = { uploads = {} }
  name_suffix   = var.bucket_name_suffix
  kms_key       = { id = module.kms.key_ids["data"] }
  writers       = { api = module.kubernetes.workload_identities["api"].principal }
  force_destroy = var.environment == "dev"
}

module "secrets" {
  source              = "../../modules/secrets/azure"
  context             = local.context
  secret_names        = values(local.app_secrets)
  readers             = { external-secrets = module.kubernetes.workload_identities["external-secrets"].principal }
  kms_key             = { id = module.kms.key_ids["data"] }
  deletion_protection = var.deletion_protection
}

# ---- managed data stores (S-3) ------------------------------------------------------------------------------------

module "postgres" {
  source                = "../../modules/postgres/azure"
  context               = local.context
  network_id            = module.network.network_id
  subnet_ids            = [module.network.cloud.postgres_subnet_id]
  allowed_cidrs         = [module.network.cidr]
  instance_size         = var.data_stores.postgres.instance_size
  storage_gb            = var.data_stores.postgres.storage_gb
  high_availability     = var.data_stores.postgres.high_availability
  backup_retention_days = var.data_stores.postgres.backup_retention_days
  kms_key               = { id = module.kms.key_ids["data"] }
  secret_store          = module.secrets.store
  deletion_protection   = var.deletion_protection
}

module "cache" {
  source              = "../../modules/cache/azure"
  context             = local.context
  network_id          = module.network.network_id
  subnet_ids          = module.network.data_subnet_ids
  allowed_cidrs       = [module.network.cidr]
  node_size           = var.data_stores.cache.node_size
  replicas            = var.data_stores.cache.replicas
  kms_key             = { id = module.kms.key_ids["data"] }
  secret_store        = module.secrets.store
  deletion_protection = var.deletion_protection
}

# The topic catalogue deploy/kafka/topics.yaml (S-25): on Azure every topic is an event hub (ARM resource).
module "kafka_topics" {
  source = "../../modules/kafka/catalogue"
}

module "kafka" {
  source              = "../../modules/kafka/azure"
  context             = local.context
  network_id          = module.network.network_id
  subnet_ids          = module.network.data_subnet_ids
  allowed_cidrs       = [module.network.cidr]
  tier                = var.data_stores.kafka.tier
  capacity            = var.data_stores.kafka.capacity
  storage_gb          = var.data_stores.kafka.storage_gb
  kms_key             = { id = module.kms.key_ids["data"] }
  secret_store        = module.secrets.store
  deletion_protection = var.deletion_protection
  topics              = module.kafka_topics.topics
}

# Elastic Cloud, reachable only from the cluster's NAT egress IPs.
module "search" {
  source              = "../../modules/search/azure"
  context             = local.context
  network_id          = module.network.network_id
  subnet_ids          = module.network.data_subnet_ids
  allowed_cidrs       = [for ip in module.network.cloud.nat_public_ips : "${ip}/32"]
  size                = var.data_stores.search.size
  zone_count          = var.data_stores.search.zone_count
  kms_key             = { id = module.kms.key_ids["data"] }
  secret_store        = module.secrets.store
  deletion_protection = var.deletion_protection
}
