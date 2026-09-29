# AWS KMS: one key per entry. encrypt = symmetric, rotated yearly; sign = ECC_NIST_P256 SIGN_VERIFY (ES256 tokens).
# Access is granted with IAM policies on the users' roles (the key policy delegates to IAM), so keys never depend on
# the roles that use them.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account = data.aws_caller_identity.current.account_id

  grants = merge([
    for key, users in var.key_users : {
      for label, principal in users : "${key}/${label}" => { key = key, principal = principal }
    }
  ]...)
}

resource "aws_kms_key" "this" {
  for_each                 = var.keys
  description              = "${var.context.name} ${each.key} (${each.value.usage})"
  key_usage                = each.value.usage == "sign" ? "SIGN_VERIFY" : "ENCRYPT_DECRYPT"
  customer_master_key_spec = each.value.usage == "sign" ? "ECC_NIST_P256" : "SYMMETRIC_DEFAULT"
  enable_key_rotation      = each.value.usage == "encrypt"
  rotation_period_in_days  = each.value.usage == "encrypt" ? each.value.rotation_days : null
  deletion_window_in_days  = var.deletion_protection ? 30 : 7

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "AccountAdministersViaIAM"
        Effect    = "Allow"
        Principal = { AWS = "arn:${data.aws_partition.current.partition}:iam::${local.account}:root" }
        Action    = "kms:*"
        Resource  = "*"
      }],
      each.value.usage == "encrypt" ? [{
        Sid       = "CloudWatchLogs"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.context.region}.amazonaws.com" }
        Action    = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
        Resource  = "*"
        Condition = {
          ArnLike = { "kms:EncryptionContext:aws:logs:arn" = "arn:${data.aws_partition.current.partition}:logs:${var.context.region}:${local.account}:*" }
        }
      }] : []
    )
  })

  tags = merge(var.context.tags, { Name = "${var.context.name}-${each.key}" })
}

resource "aws_kms_alias" "this" {
  for_each      = var.keys
  name          = "alias/${var.context.name}-${each.key}"
  target_key_id = aws_kms_key.this[each.key].key_id
}

resource "aws_iam_role_policy" "key_user" {
  for_each = local.grants
  name     = "kms-${var.context.name}-${replace(each.key, "/", "-")}"
  role     = element(split("/", each.value.principal), length(split("/", each.value.principal)) - 1)
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = var.keys[each.value.key].usage == "sign" ? [
        "kms:Sign", "kms:Verify", "kms:GetPublicKey", "kms:DescribeKey"
        ] : [
        "kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"
      ]
      Resource = aws_kms_key.this[each.value.key].arn
    }]
  })
}
