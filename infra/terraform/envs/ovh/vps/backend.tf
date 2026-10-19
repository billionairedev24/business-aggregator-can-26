# Local state by default: a demo box for a few weeks, state on one machine (it holds the S3 secret keys — keep it out
# of git; *.tfstate is ignored). A shared state instead: an Object Storage bucket in the same project as an S3
# backend — copy backend.hcl.example to backend.hcl, uncomment below, then
#   terraform init -backend-config=backend.hcl -migrate-state
#
# terraform {
#   backend "s3" {}
# }
