output "registry_url" {
  description = "Registry host for docker login / image names."
  value       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.context.region}.amazonaws.com"
}

output "repository_urls" {
  description = "Deployable => image repository URL."
  value       = { for k, v in aws_ecr_repository.this : k => v.repository_url }
}

output "cloud" {
  description = "AWS-only details."
  value = {
    login_command = "aws ecr get-login-password --region ${var.context.region} | docker login --username AWS --password-stdin ${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.context.region}.amazonaws.com"
  }
}
