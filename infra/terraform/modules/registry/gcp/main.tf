# Artifact Registry: one Docker repository per environment in the Canadian region; images are
# <region>-docker.pkg.dev/<project>/northline-<env>/<deployable>. Immutable tags and a cleanup policy.
# CMEK needs the Artifact Registry service agent on the key (granted by the stack).

resource "google_artifact_registry_repository" "this" {
  project       = var.context.project_id
  location      = var.context.region
  repository_id = var.context.name
  description   = "Northline images (${var.context.environment})"
  format        = "DOCKER"
  kms_key_name  = try(var.kms_key.id, null)
  labels        = var.context.tags

  docker_config {
    immutable_tags = true
  }

  cleanup_policy_dry_run = false

  cleanup_policies {
    id     = "keep-newest"
    action = "KEEP"
    most_recent_versions {
      keep_count = var.keep_images
    }
  }

  cleanup_policies {
    id     = "delete-untagged"
    action = "DELETE"
    condition {
      tag_state  = "UNTAGGED"
      older_than = "604800s"
    }
  }
}

resource "google_artifact_registry_repository_iam_member" "readers" {
  for_each   = var.readers
  project    = var.context.project_id
  location   = google_artifact_registry_repository.this.location
  repository = google_artifact_registry_repository.this.name
  role       = "roles/artifactregistry.reader"
  member     = each.value
}
