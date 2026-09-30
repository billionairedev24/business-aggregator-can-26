# Northline on AWS: composes the capability modules for one environment. Env roots (envs/aws/<env>) only choose
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
    # S-12 Stripe webhook signing secrets, S-13 email (unsubscribe HMAC key, SendGrid/Azure key, SMTP relay password).
    STRIPE_WEBHOOK_SECRET         = "stripe-webhook-secret"
    STRIPE_CONNECT_WEBHOOK_SECRET = "stripe-connect-webhook-secret"
    EMAIL_UNSUBSCRIBE_KEY         = "email-unsubscribe-key"
    EMAIL_API_KEY                 = "email-api-key"
    SMTP_PASSWORD                 = "smtp-password"
  }
}

module "network" {
  source                = "../../modules/network/aws"
  context               = local.context
  cidr                  = var.cidr
  zone_count            = var.zone_count
  high_availability_nat = var.high_availability_nat
}

module "kms" {
  source  = "../../modules/kms/aws"
  context = local.context
  keys = {
    data    = { usage = "encrypt" }
    signing = { usage = "sign" }
  }
  key_users = {
    # Only northline-auth signs tokens (S-7); the api and bff verify through the JWK set, not the KMS.
    signing = {
      auth = module.kubernetes.workload_identities["auth"].principal
    }
  }
  deletion_protection = var.deletion_protection
}

module "kubernetes" {
  source              = "../../modules/kubernetes/aws"
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
  source  = "../../modules/registry/aws"
  context = local.context
  kms_key = { id = module.kms.key_ids["data"] }
  readers = { nodes = module.kubernetes.node_identity }
}

module "dns" {
  source    = "../../modules/dns/aws"
  context   = local.context
  zone_name = var.dns_zone_name
}

module "storage" {
  source        = "../../modules/storage/aws"
  context       = local.context
  buckets       = { uploads = {} }
  name_suffix   = var.bucket_name_suffix
  kms_key       = { id = module.kms.key_ids["data"] }
  writers       = { api = module.kubernetes.workload_identities["api"].principal }
  force_destroy = var.environment == "dev"
}

module "secrets" {
  source              = "../../modules/secrets/aws"
  context             = local.context
  secret_names        = values(local.app_secrets)
  readers             = { external-secrets = module.kubernetes.workload_identities["external-secrets"].principal }
  kms_key             = { id = module.kms.key_ids["data"] }
  deletion_protection = var.deletion_protection
}

# ---- managed data stores (S-3) ------------------------------------------------------------------------------------

module "postgres" {
  source                = "../../modules/postgres/aws"
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
}

module "cache" {
  source              = "../../modules/cache/aws"
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

module "kafka" {
  source              = "../../modules/kafka/aws"
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
}

# Elastic Cloud, reachable only from the cluster's NAT egress IPs.
module "search" {
  source              = "../../modules/search/aws"
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

# ---- SMS and voice codes through AWS End User Messaging (S-8, optional) -------------------------------------------
# The origination number itself is requested by hand (Canadian long code / toll-free registration, SMS sandbox exit,
# spend limit — docs/runbooks/README.md § SMS and voice codes); Terraform only grants northline-auth the two send
# actions on it and fills SMS_PROVIDER / SMS_FROM / SMS_REGION. Without it the environment uses Twilio (manual inputs).

resource "aws_iam_role_policy" "auth_sms" {
  count = var.sms_origination_identity == null ? 0 : 1
  name  = "sms-voice-${local.name}"
  role  = element(split("/", module.kubernetes.workload_identities["auth"].principal), length(split("/", module.kubernetes.workload_identities["auth"].principal)) - 1)
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sms-voice:SendTextMessage", "sms-voice:SendVoiceMessage"]
      Resource = var.sms_origination_identity
    }]
  })
}
