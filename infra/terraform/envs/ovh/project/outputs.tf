output "project_id" {
  description = "The Public Cloud project id (32 hex characters): OVH_SERVICE_NAME."
  value       = ovh_cloud_project.dryrun.project_id
}
