# The Terraform derivation matches the catalogue's rules (the Java provisioner and scripts/topics.sh are compared in
# server/worker TopicCatalogueTest). Run: terraform init -backend=false && terraform test
run "derives_topics_dlq_and_retry_topics" {
  command = plan

  assert {
    condition     = output.topics["payments.payout"] == { partitions = 6, retention_hours = 168, cleanup_policy = "delete" }
    error_message = "A topic takes the catalogue defaults."
  }

  assert {
    condition     = output.topics["payments.payout.dlq"] == { partitions = 1, retention_hours = 720, cleanup_policy = "delete" }
    error_message = "Every topic has a .dlq with the dlq settings."
  }

  assert {
    condition     = output.topics["catalogue.listing.search-indexer.retry-2"] == { partitions = 6, retention_hours = 24, cleanup_policy = "delete" }
    error_message = "Consumers get <topic>.<group>.retry-<n>, one per delay, with the source topic's partitions."
  }

  assert {
    condition     = !contains(keys(output.topics), "catalogue.listing.search-indexer.retry-3")
    error_message = "One retry topic per delay, no more."
  }

  assert {
    condition     = length(output.topics) <= 100
    error_message = "Event Hubs Premium holds 100 event hubs per processing unit."
  }
}
