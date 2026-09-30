output "zone_id" {
  description = "Hosted zone id."
  value       = aws_route53_zone.this.zone_id
}

output "zone_name" {
  description = "Zone name."
  value       = aws_route53_zone.this.name
}

output "name_servers" {
  description = "NS records to create in the parent zone / at the registrar."
  value       = aws_route53_zone.this.name_servers
}

output "cloud" {
  description = "AWS-only details."
  value       = { zone_arn = aws_route53_zone.this.arn }
}
