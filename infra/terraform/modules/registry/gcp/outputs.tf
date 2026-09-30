output "registry_url" {
  description = "Registry host + repository path."
  value       = "${var.context.region}-docker.pkg.dev/${var.context.project_id}/${google_artifact_registry_repository.this.repository_id}"
}

output "repository_urls" {
  description = "Deployable => image repository URL."
  value       = { for r in var.repositories : r => "${var.context.region}-docker.pkg.dev/${var.context.project_id}/${google_artifact_registry_repository.this.repository_id}/${r}" }
}

output "cloud" {
  description = "Google Cloud-only details."
  value = {
    login_command = "gcloud auth configure-docker ${var.context.region}-docker.pkg.dev"
  }
}
