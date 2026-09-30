output "topics" {
  description = "Every topic that must exist: name => { partitions, retention_hours, cleanup_policy }."
  value       = merge(local.main, local.dead_letter, local.retries)
}
