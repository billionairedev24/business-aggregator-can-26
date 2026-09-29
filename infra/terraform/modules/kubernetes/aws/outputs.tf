output "cluster_name" {
  description = "EKS cluster name."
  value       = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  description = "Kubernetes API URL."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_ca_certificate" {
  description = "Cluster CA (base64 PEM)."
  value       = aws_eks_cluster.this.certificate_authority[0].data
  sensitive   = true
}

output "oidc_issuer_url" {
  description = "Service-account token issuer (IRSA)."
  value       = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

output "node_identity" {
  description = "IAM role ARN of the nodes (image pulls)."
  value       = aws_iam_role.node.arn
}

output "workload_identities" {
  description = "Per workload: the principal to grant (IAM role ARN) and the annotations/labels for its Kubernetes ServiceAccount and pods (Helm, S-14)."
  value = {
    for k, v in var.workload_identities : k => {
      principal                   = aws_iam_role.workload[k].arn
      namespace                   = v.namespace
      service_account             = v.service_account
      service_account_annotations = { "eks.amazonaws.com/role-arn" = aws_iam_role.workload[k].arn }
      pod_labels                  = {}
    }
  }
}

output "kubeconfig_command" {
  description = "Command that writes a kubeconfig entry for this cluster."
  value       = "aws eks update-kubeconfig --region ${var.context.region} --name ${aws_eks_cluster.this.name}"
}

output "cloud" {
  description = "AWS-only details."
  value = {
    cluster_arn               = aws_eks_cluster.this.arn
    oidc_provider_arn         = aws_iam_openid_connect_provider.this.arn
    cluster_security_group_id = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  }
}
