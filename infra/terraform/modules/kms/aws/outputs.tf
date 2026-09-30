output "kms_provider" {
  description = "Value for KMS_PROVIDER."
  value       = "aws"
}

output "key_ids" {
  description = "Key name => key ARN (for other modules' kms_key_id)."
  value       = { for k, v in aws_kms_key.this : k => v.arn }
}

output "key_refs" {
  description = "Key name => value for KMS_KEY_ID: the key ARN (AWS rotates signing keys by creating a new key, not a version)."
  value       = { for k, v in aws_kms_key.this : k => v.arn }
}

output "cloud" {
  description = "AWS-only details."
  value = {
    aliases = { for k, v in aws_kms_alias.this : k => v.name }
  }
}
