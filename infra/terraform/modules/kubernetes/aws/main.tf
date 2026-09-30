# Amazon EKS: managed control plane (API auth mode, secrets encrypted with KMS), managed node groups per pool,
# IRSA (IAM roles for service accounts) for every workload identity, and the core add-ons.

data "aws_partition" "current" {}

locals {
  partition = data.aws_partition.current.partition
  oidc_host = replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")
}

# ---- control plane ---------------------------------------------------------------------------------------------

resource "aws_iam_role" "cluster" {
  name = "${var.context.name}-eks-cluster"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
  tags = var.context.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_iam_role_policy" "cluster_kms" {
  count = var.kms_key == null ? 0 : 1
  name  = "secrets-envelope-encryption"
  role  = aws_iam_role.cluster.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["kms:Encrypt", "kms:Decrypt", "kms:DescribeKey", "kms:CreateGrant"]
      Resource = try(var.kms_key.id, null)
    }]
  })
}

resource "aws_cloudwatch_log_group" "cluster" {
  name              = "/aws/eks/${var.context.name}/cluster"
  retention_in_days = var.context.environment == "prod" ? 90 : 14
  kms_key_id        = try(var.kms_key.id, null)
  tags              = var.context.tags
}

resource "aws_eks_cluster" "this" {
  name                      = var.context.name
  role_arn                  = aws_iam_role.cluster.arn
  version                   = var.kubernetes_version
  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
  deletion_protection       = var.deletion_protection

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true
  }

  vpc_config {
    subnet_ids              = var.subnet_ids
    endpoint_private_access = true
    endpoint_public_access  = true
    public_access_cidrs     = length(var.api_allowed_cidrs) > 0 ? var.api_allowed_cidrs : ["0.0.0.0/0"]
  }

  dynamic "encryption_config" {
    for_each = var.kms_key == null ? [] : [try(var.kms_key.id, null)]
    content {
      resources = ["secrets"]
      provider {
        key_arn = encryption_config.value
      }
    }
  }

  upgrade_policy {
    support_type = var.context.environment == "prod" ? "EXTENDED" : "STANDARD"
  }

  tags = var.context.tags

  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_iam_role_policy.cluster_kms,
    aws_cloudwatch_log_group.cluster,
  ]
}

resource "aws_eks_access_entry" "admin" {
  for_each      = toset(var.admin_principals)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  tags          = var.context.tags
}

resource "aws_eks_access_policy_association" "admin" {
  for_each      = toset(var.admin_principals)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  policy_arn    = "arn:${local.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.admin]
}

# ---- nodes -----------------------------------------------------------------------------------------------------

resource "aws_iam_role" "node" {
  name = "${var.context.name}-eks-node"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.context.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    "AmazonEC2ContainerRegistryReadOnly",
    "AmazonSSMManagedInstanceCore",
  ])
  role       = aws_iam_role.node.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/${each.value}"
}

resource "aws_eks_node_group" "this" {
  for_each        = var.node_pools
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = each.key
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.subnet_ids
  instance_types  = [each.value.machine_type]
  capacity_type   = each.value.spot ? "SPOT" : "ON_DEMAND"
  ami_type        = can(regex("^[a-z]+[0-9]+g", each.value.machine_type)) ? "AL2023_ARM_64_STANDARD" : "AL2023_x86_64_STANDARD"
  disk_size       = each.value.disk_size_gb
  labels          = merge(each.value.labels, { "northline.ca/pool" = each.key })

  scaling_config {
    min_size     = each.value.min_count
    max_size     = each.value.max_count
    desired_size = max(each.value.min_count, 1)
  }

  update_config {
    max_unavailable = 1
  }

  tags = var.context.tags

  # The cluster autoscaler (S-14) owns desired_size after creation.
  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# ---- IRSA ------------------------------------------------------------------------------------------------------

resource "aws_iam_openid_connect_provider" "this" {
  url            = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list = ["sts.amazonaws.com"]
  tags           = var.context.tags
}

locals {
  # Workload identities plus the EBS CSI driver's own role.
  irsa = merge(var.workload_identities, {
    ebs-csi = { namespace = "kube-system", service_account = "ebs-csi-controller-sa" }
  })
}

resource "aws_iam_role" "workload" {
  for_each = local.irsa
  name     = "${var.context.name}-${each.key}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.this.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:sub" = "system:serviceaccount:${each.value.namespace}:${each.value.service_account}"
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
  tags = merge(var.context.tags, { "northline.ca/service-account" = "${each.value.namespace}/${each.value.service_account}" })
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.workload["ebs-csi"].name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

# ---- add-ons ---------------------------------------------------------------------------------------------------

resource "aws_eks_addon" "this" {
  for_each                    = toset(["vpc-cni", "kube-proxy", "coredns", "aws-ebs-csi-driver"])
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = each.value
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  service_account_role_arn    = each.value == "aws-ebs-csi-driver" ? aws_iam_role.workload["ebs-csi"].arn : null
  tags                        = var.context.tags

  depends_on = [aws_eks_node_group.this]
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.network_id]
}
