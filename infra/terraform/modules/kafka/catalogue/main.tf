# The Kafka topic catalogue (S-25) as Terraform data: reads deploy/kafka/topics.yaml — the same file the provisioning
# Job (ca.northline.worker.topics) and scripts/topics.sh read — and derives the same topic set: every topic, its
# `.dlq`, and `<topic>.<group>.retry-<n>` per consumer and retry delay. No resources: cloud implementations that
# create topics as cloud resources (Azure Event Hubs) take `topics` from here. Cloud-neutral, so it is not a
# capability with aws/gcp/azure implementations.

locals {
  catalogue = yamldecode(file(coalesce(var.catalogue_file, "${path.module}/../../../../../deploy/kafka/topics.yaml")))
  defaults  = local.catalogue.defaults
  dlq       = local.catalogue.dlq
  retry     = local.catalogue.retry

  main = {
    for t in local.catalogue.topics : t.name => {
      partitions      = try(t.partitions, local.defaults.partitions)
      retention_hours = try(t.retentionHours, local.defaults.retentionHours)
      cleanup_policy  = try(t.cleanupPolicy, local.defaults.cleanupPolicy)
    }
  }

  dead_letter = {
    for name, _ in local.main : "${name}.dlq" => {
      partitions      = local.dlq.partitions
      retention_hours = local.dlq.retentionHours
      cleanup_policy  = local.defaults.cleanupPolicy
    }
  }

  retries = merge(flatten([
    for c in try(local.catalogue.consumers, []) : [
      for t in c.topics : {
        for i, _ in c.retryDelaysSeconds : "${t}.${c.group}.retry-${i}" => {
          partitions      = local.main[t].partitions
          retention_hours = local.retry.retentionHours
          cleanup_policy  = local.defaults.cleanupPolicy
        }
      }
    ]
  ])...)
}
