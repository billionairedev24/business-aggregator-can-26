output "network_id" {
  description = "VPC id."
  value       = aws_vpc.this.id
}

output "network_name" {
  description = "VPC name tag."
  value       = var.context.name
}

output "cidr" {
  description = "VPC CIDR."
  value       = aws_vpc.this.cidr_block
}

output "cluster_subnet_ids" {
  description = "Private subnets for EKS nodes (egress through NAT)."
  value       = aws_subnet.private[*].id
}

output "data_subnet_ids" {
  description = "Isolated subnets for RDS, ElastiCache and MSK (no internet route)."
  value       = aws_subnet.data[*].id
}

output "cloud" {
  description = "AWS-only details."
  value = {
    public_subnet_ids  = aws_subnet.public[*].id
    nat_public_ips     = aws_eip.nat[*].public_ip
    availability_zones = local.azs
  }
}
