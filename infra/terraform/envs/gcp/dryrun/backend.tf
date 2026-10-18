# Remote state. Until the state bucket exists (bootstrap/gcp), state stays local. Then uncomment and run
#   terraform init -backend-config=backend.hcl -migrate-state
# with backend.hcl copied from backend.hcl.example.
#
# terraform {
#   backend "gcs" {}
# }
