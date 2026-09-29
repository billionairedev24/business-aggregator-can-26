# Cloud KMS: one key ring per environment in the Canadian region; encrypt = symmetric ENCRYPT_DECRYPT rotated on a
# schedule, sign = ASYMMETRIC_SIGN EC P-256 (ES256 tokens, S-7). Key rings cannot be deleted in Google Cloud: a
# re-created environment needs a new context.name or an import.

locals {
  grants = merge([
    for key, users in var.key_users : {
      for label, principal in users : "${key}/${label}" => { key = key, principal = principal }
    }
  ]...)
}

resource "google_kms_key_ring" "this" {
  project  = var.context.project_id
  name     = var.context.name
  location = var.context.region
}

resource "google_kms_crypto_key" "this" {
  for_each                   = var.keys
  name                       = each.key
  key_ring                   = google_kms_key_ring.this.id
  purpose                    = each.value.usage == "sign" ? "ASYMMETRIC_SIGN" : "ENCRYPT_DECRYPT"
  rotation_period            = each.value.usage == "encrypt" ? "${each.value.rotation_days * 86400}s" : null
  destroy_scheduled_duration = var.deletion_protection ? "2592000s" : "86400s"
  labels                     = var.context.tags

  version_template {
    algorithm        = each.value.usage == "sign" ? "EC_SIGN_P256_SHA256" : "GOOGLE_SYMMETRIC_ENCRYPTION"
    protection_level = var.context.environment == "prod" ? "HSM" : "SOFTWARE"
  }
}

resource "google_kms_crypto_key_iam_member" "encrypt" {
  for_each      = { for k, v in local.grants : k => v if var.keys[v.key].usage == "encrypt" }
  crypto_key_id = google_kms_crypto_key.this[each.value.key].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = each.value.principal
}

resource "google_kms_crypto_key_iam_member" "sign" {
  for_each      = { for k, v in local.grants : k => v if var.keys[v.key].usage == "sign" }
  crypto_key_id = google_kms_crypto_key.this[each.value.key].id
  role          = "roles/cloudkms.signerVerifier"
  member        = each.value.principal
}

resource "google_kms_crypto_key_iam_member" "public_key" {
  for_each      = { for k, v in local.grants : k => v if var.keys[v.key].usage == "sign" }
  crypto_key_id = google_kms_crypto_key.this[each.value.key].id
  role          = "roles/cloudkms.publicKeyViewer"
  member        = each.value.principal
}
