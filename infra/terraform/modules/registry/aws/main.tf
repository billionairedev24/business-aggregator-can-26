# Amazon ECR: one private repository per deployable (northline/<name>), immutable tags, scan on push,
# KMS encryption, and a lifecycle policy keeping the newest images.

data "aws_caller_identity" "current" {}

resource "aws_ecr_repository" "this" {
  for_each             = toset(var.repositories)
  name                 = "northline/${each.value}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.context.environment == "dev"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = var.kms_key == null ? "AES256" : "KMS"
    kms_key         = try(var.kms_key.id, null)
  }

  tags = var.context.tags
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 7 days"
        selection    = { tagStatus = "untagged", countType = "sinceImagePushed", countUnit = "days", countNumber = 7 }
        action       = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the newest ${var.keep_images} images"
        selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = var.keep_images }
        action       = { type = "expire" }
      },
    ]
  })
}

resource "aws_ecr_repository_policy" "readers" {
  for_each   = length(var.readers) == 0 ? {} : aws_ecr_repository.this
  repository = each.value.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "Pull"
      Effect    = "Allow"
      Principal = { AWS = values(var.readers) }
      Action    = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
    }]
  })
}
