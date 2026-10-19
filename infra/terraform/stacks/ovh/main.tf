# OVHcloud Object Storage (S3 API) in the region, and one S3 user per writer, each limited to its bucket by an S3
# policy: the api writes uploads (STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY), CloudNativePG writes the in-cluster
# Postgres's base backups and WAL. Encrypted at rest by the bucket's default encryption (SSE-OMK, OVHcloud-managed
# keys): OVHcloud has no SSE-KMS the storage port could name, so STORAGE_ENCRYPTION_KEY stays empty.

locals {
  # Per Public Cloud region: the Object Storage region and its S3 endpoint (Standard tier), the Managed Databases
  # region. A new Canadian region is one entry here once its services are confirmed.
  regions = {
    BHS5 = {
      storage_region = "BHS"
      s3_region      = "bhs"
      s3_endpoint    = "https://s3.bhs.io.cloud.ovh.net"
      database       = "BHS"
    }
  }
  r    = local.regions[var.region]
  name = "northline-${var.target}"
  ds   = var.data_services

  in_cluster = { for s in ["postgres", "cache", "search", "kafka"] : s => local.ds[s].mode == "in-cluster" }

  # In-cluster endpoints: the Services of deploy/helm/northline-data in the namespace.
  svc = "${var.namespace}.svc.cluster.local"
  endpoints = {
    db_url          = local.in_cluster.postgres ? "jdbc:postgresql://northline-pg-rw.${local.svc}:5432/northline?sslmode=require" : local.ds.postgres.db_url
    db_user         = local.ds.postgres.db_user
    redis_host      = local.in_cluster.cache ? "northline-valkey.${local.svc}" : local.ds.cache.host
    redis_port      = local.in_cluster.cache ? 6379 : local.ds.cache.port
    redis_ssl       = local.in_cluster.cache ? false : local.ds.cache.ssl
    redis_username  = local.in_cluster.cache ? "" : local.ds.cache.username
    meilisearch_url = local.in_cluster.search ? "http://northline-meilisearch.${local.svc}:7700" : local.ds.search.url
    kafka_bootstrap = local.in_cluster.kafka ? "northline-kafka.${local.svc}:9092" : local.ds.kafka.bootstrap
    kafka_protocol  = local.in_cluster.kafka ? "PLAINTEXT" : local.ds.kafka.security_protocol
    kafka_mechanism = local.in_cluster.kafka ? "" : local.ds.kafka.sasl_mechanism
  }

  buckets = merge(
    { uploads = "${local.name}-uploads${var.bucket_name_suffix}" },
    local.in_cluster.postgres ? { pg-backups = "${local.name}-pg-backups${var.bucket_name_suffix}" } : {},
  )
  s3_actions = [
    "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "s3:GetBucketLocation",
    "s3:ListMultipartUploadParts", "s3:ListBucketMultipartUploads", "s3:AbortMultipartUpload",
  ]
}

resource "ovh_cloud_project_storage" "bucket" {
  for_each     = local.buckets
  service_name = var.service_name
  region_name  = local.r.storage_region
  name         = each.value
  hide_objects = true # never list the objects into the state

  encryption = {
    sse_algorithm = "AES256" # SSE-OMK: the bucket's default encryption with OVHcloud-managed keys
  }
}

# One user per writer: role objectstore_operator gives it S3 credentials, the S3 policy narrows it to its bucket.
resource "ovh_cloud_project_user" "s3" {
  for_each     = local.buckets
  service_name = var.service_name
  description  = "${local.name} ${each.key} (Terraform, docs/runbooks/ovh.md)"
  role_names   = ["objectstore_operator"]
}

resource "ovh_cloud_project_user_s3_credential" "s3" {
  for_each     = local.buckets
  service_name = var.service_name
  user_id      = ovh_cloud_project_user.s3[each.key].id
}

resource "ovh_cloud_project_user_s3_policy" "s3" {
  for_each     = local.buckets
  service_name = var.service_name
  user_id      = ovh_cloud_project_user.s3[each.key].id
  policy = jsonencode({
    Statement = [{
      Sid      = "Bucket${replace(title(replace(each.key, "-", " ")), " ", "")}"
      Effect   = "Allow"
      Action   = local.s3_actions
      Resource = ["arn:aws:s3:::${ovh_cloud_project_storage.bucket[each.key].name}", "arn:aws:s3:::${ovh_cloud_project_storage.bucket[each.key].name}/*"]
    }]
  })
}

# CloudNativePG prunes its backups by retention; this deletes what it might leave (aborted uploads, orphans) later.
resource "ovh_cloud_project_storage_object_bucket_lifecycle_configuration" "pg_backups" {
  count          = local.in_cluster.postgres ? 1 : 0
  service_name   = var.service_name
  region_name    = local.r.storage_region
  container_name = ovh_cloud_project_storage.bucket["pg-backups"].name
  rules = [{
    id                                = "safety-net"
    status                            = "enabled"
    expiration                        = { days = var.postgres_backup_retention_days * 4 }
    abort_incomplete_multipart_upload = { days_after_initiation = 2 }
  }]
}
