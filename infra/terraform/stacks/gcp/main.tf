# Northline on Google Cloud: composes the capability modules for one environment. Env roots (envs/gcp/<env>) only choose
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
    project_id  = var.project_id
    name        = local.name
    environment = var.environment
    region      = var.region
    tags        = local.tags
  }

  # Kubernetes service accounts that get a cloud identity (Helm charts, S-14, use these names).
  workload_identities = {
    api              = { namespace = local.namespace, service_account = "northline-api" }
    auth             = { namespace = local.namespace, service_account = "northline-auth" }
    bff              = { namespace = local.namespace, service_account = "northline-bff" }
    worker           = { namespace = local.namespace, service_account = "northline-worker" }
    external-secrets = { namespace = "external-secrets", service_account = "external-secrets" }
  }

  # Application secrets created empty; an operator sets the values (docs/runbooks/<env>.md § Environment variables).
  app_secrets = {
    TOTP_KEY                 = "totp-key"
    WEBHOOK_SECRET_KEY       = "webhook-secret-key"
    STUDIO_BFF_SECRET        = "studio-bff-secret"
    STUDIO_BFF_SECRET_HASH   = "studio-bff-secret-hash"
    CONSUMER_BFF_SECRET_HASH = "consumer-bff-secret-hash"
    CONSOLE_BFF_SECRET_HASH  = "console-bff-secret-hash"
    STRIPE_SECRET_KEY        = "stripe-secret-key"
    STRIPE_PUBLISHABLE_KEY   = "stripe-publishable-key"
    GOOGLE_CLIENT_SECRET     = "google-client-secret"
    APPLE_CLIENT_SECRET      = "apple-client-secret"
    SMS_AUTH_TOKEN           = "sms-auth-token"
  }


  # APIs this stack needs. Enabling is idempotent; they stay enabled on destroy.
  services = [
    "artifactregistry.googleapis.com",
    "cloudkms.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "compute.googleapis.com",
    "container.googleapis.com",
    "dns.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "managedkafka.googleapis.com",
    "memorystore.googleapis.com",
    "networkconnectivity.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "sqladmin.googleapis.com",
    "storage.googleapis.com",
  ]

  # Google-managed service agents that encrypt with the data key (CMEK).
  cmek_service_agents = [
    "artifactregistry.googleapis.com",
    "managedkafka.googleapis.com",
    "memorystore.googleapis.com",
    "secretmanager.googleapis.com",
    "sqladmin.googleapis.com",
  ]
}

resource "google_project_service" "this" {
  for_each           = toset(local.services)
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_project_service_identity" "cmek" {
  provider   = google-beta
  for_each   = toset(local.cmek_service_agents)
  project    = var.project_id
  service    = each.value
  depends_on = [google_project_service.this]
}

data "google_storage_project_service_account" "this" {
  project    = var.project_id
  depends_on = [google_project_service.this]
}

module "network" {
  source                = "../../modules/network/gcp"
  context               = local.context
  cidr                  = var.cidr
  zone_count            = var.zone_count
  high_availability_nat = var.high_availability_nat
  depends_on            = [google_project_service.this]
}

module "kms" {
  source  = "../../modules/kms/gcp"
  context = local.context
  keys = {
    data    = { usage = "encrypt" }
    signing = { usage = "sign" }
  }
  key_users = {
    data = merge(
      { for s in local.cmek_service_agents : split(".", s)[0] => google_project_service_identity.cmek[s].member },
      { storage = "serviceAccount:${data.google_storage_project_service_account.this.email_address}" },
    )
    signing = {
      api  = module.kubernetes.workload_identities["api"].principal
      auth = module.kubernetes.workload_identities["auth"].principal
    }
  }
  deletion_protection = var.deletion_protection
  depends_on          = [google_project_service.this]
}

module "kubernetes" {
  source              = "../../modules/kubernetes/gcp"
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

# CMEK users below need the service-agent grants from module.kms first.
module "registry" {
  source     = "../../modules/registry/gcp"
  context    = local.context
  kms_key    = { id = module.kms.key_ids["data"] }
  readers    = { nodes = module.kubernetes.node_identity }
  depends_on = [module.kms]
}

module "dns" {
  source     = "../../modules/dns/gcp"
  context    = local.context
  zone_name  = var.dns_zone_name
  depends_on = [google_project_service.this]
}

module "storage" {
  source        = "../../modules/storage/gcp"
  context       = local.context
  buckets       = { uploads = {} }
  name_suffix   = var.bucket_name_suffix
  kms_key       = { id = module.kms.key_ids["data"] }
  writers       = { api = module.kubernetes.workload_identities["api"].principal }
  force_destroy = var.environment == "dev"
  depends_on    = [module.kms]
}

module "secrets" {
  source              = "../../modules/secrets/gcp"
  context             = local.context
  secret_names        = values(local.app_secrets)
  readers             = { external-secrets = module.kubernetes.workload_identities["external-secrets"].principal }
  kms_key             = { id = module.kms.key_ids["data"] }
  deletion_protection = var.deletion_protection
  depends_on          = [module.kms]
}

# ---- managed data stores (S-3) ------------------------------------------------------------------------------------

module "postgres" {
  source                = "../../modules/postgres/gcp"
  context               = local.context
  network_id            = module.network.network_id
  subnet_ids            = module.network.data_subnet_ids
  allowed_cidrs         = [module.network.cidr]
  instance_size         = var.data_stores.postgres.instance_size
  storage_gb            = var.data_stores.postgres.storage_gb
  high_availability     = var.data_stores.postgres.high_availability
  backup_retention_days = var.data_stores.postgres.backup_retention_days
  kms_key               = { id = module.kms.key_ids["data"] }
  secret_store          = module.secrets.store
  deletion_protection   = var.deletion_protection
  depends_on            = [module.network, module.kms] # Private Service Access; CMEK grant
}

module "cache" {
  source              = "../../modules/cache/gcp"
  context             = local.context
  network_id          = module.network.network_id
  subnet_ids          = module.network.data_subnet_ids
  allowed_cidrs       = [module.network.cidr]
  node_size           = var.data_stores.cache.node_size
  replicas            = var.data_stores.cache.replicas
  kms_key             = { id = module.kms.key_ids["data"] }
  secret_store        = module.secrets.store
  deletion_protection = var.deletion_protection
  depends_on          = [module.network, module.kms] # PSC policy; CMEK grant
}

module "kafka" {
  source              = "../../modules/kafka/gcp"
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
  depends_on          = [module.kms] # CMEK grant
}

# Elastic Cloud, reachable only from the cluster's NAT egress IPs.
module "search" {
  source              = "../../modules/search/gcp"
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
