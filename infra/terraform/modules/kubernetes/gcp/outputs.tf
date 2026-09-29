output "cluster_name" {
  description = "GKE cluster name."
  value       = google_container_cluster.this.name
}

output "cluster_endpoint" {
  description = "Kubernetes API URL."
  value       = "https://${google_container_cluster.this.endpoint}"
}

output "cluster_ca_certificate" {
  description = "Cluster CA (base64 PEM)."
  value       = one(google_container_cluster.this.master_auth[*].cluster_ca_certificate)
  sensitive   = true
}

output "oidc_issuer_url" {
  description = "Service-account token issuer."
  value       = "https://container.googleapis.com/v1/projects/${var.context.project_id}/locations/${google_container_cluster.this.location}/clusters/${google_container_cluster.this.name}"
}

output "node_identity" {
  description = "IAM member of the node service account (image pulls)."
  value       = "serviceAccount:${google_service_account.nodes.email}"
}

output "workload_identities" {
  description = "Per workload: the principal to grant (IAM member of its Google service account) and the annotations/labels for its Kubernetes ServiceAccount and pods (Helm, S-14)."
  value = {
    for k, v in var.workload_identities : k => {
      principal                   = "serviceAccount:${google_service_account.workload[k].email}"
      namespace                   = v.namespace
      service_account             = v.service_account
      service_account_annotations = { "iam.gke.io/gcp-service-account" = google_service_account.workload[k].email }
      pod_labels                  = {}
    }
  }
}

output "kubeconfig_command" {
  description = "Command that writes a kubeconfig entry for this cluster."
  value       = "gcloud container clusters get-credentials ${google_container_cluster.this.name} --location ${google_container_cluster.this.location} --project ${var.context.project_id}"
}

output "cloud" {
  description = "Google Cloud-only details."
  value = {
    location      = google_container_cluster.this.location
    workload_pool = local.workload_pool
    node_sa_email = google_service_account.nodes.email
  }
}
