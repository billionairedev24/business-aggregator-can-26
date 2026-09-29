# AWS Secrets Manager: every secret of the environment lives under northline/<env>/ and is encrypted with the data
# key. Readers (External Secrets Operator) may read the whole prefix, including the secrets the data-store modules
# create (S-3). Secrets are created empty: values are put by an operator (aws secretsmanager put-secret-value).

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  prefix     = "northline/${var.context.environment}/"
  prefix_arn = "arn:${data.aws_partition.current.partition}:secretsmanager:${var.context.region}:${data.aws_caller_identity.current.account_id}:secret:${local.prefix}*"
}

resource "aws_secretsmanager_secret" "this" {
  for_each                = toset(var.secret_names)
  name                    = "${local.prefix}${each.value}"
  kms_key_id              = try(var.kms_key.id, null)
  recovery_window_in_days = var.deletion_protection ? 30 : 7
  tags                    = var.context.tags
}

resource "aws_iam_role_policy" "reader" {
  for_each = var.readers
  name     = "secrets-${var.context.name}"
  role     = element(split("/", each.value), length(split("/", each.value)) - 1)
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret", "secretsmanager:ListSecretVersionIds"]
        Resource = local.prefix_arn
      }],
      var.kms_key == null ? [] : [{
        Effect    = "Allow"
        Action    = "kms:Decrypt"
        Resource  = try(var.kms_key.id, null)
        Condition = { StringEquals = { "kms:ViaService" = "secretsmanager.${var.context.region}.amazonaws.com" } }
      }]
    )
  })
}
