output "secrets_provider" {
  description = "External Secrets Operator provider for this store (SecretStore spec.provider)."
  value       = "aws"
}

output "store" {
  description = "Handle passed to the data-store modules (S-3): where and how they write generated secrets."
  value = {
    id         = local.prefix
    prefix     = local.prefix
    kms_key_id = try(var.kms_key.id, null)
  }
}

output "secret_refs" {
  description = "Secret name => reference for an ExternalSecret remoteRef.key."
  value       = { for k, v in aws_secretsmanager_secret.this : k => v.name }
}

output "cloud" {
  description = "AWS-only details."
  value = {
    secret_arns = { for k, v in aws_secretsmanager_secret.this : k => v.arn }
    region      = var.context.region
  }
}
