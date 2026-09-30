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

# S-17: what the edge add-ons need to write this zone (docs/runbooks/edge.md). The identities come from the
# kubernetes module (IRSA), so nothing here is a credential.
output "cert_manager_dns01" {
  description = "cert-manager ACME DNS-01 solver block for this zone (Issuer spec.acme.solvers[].dns01)."
  value = {
    route53 = { region = var.context.region, hostedZoneID = aws_route53_zone.this.zone_id }
  }
}

output "external_dns" {
  description = "external-dns Helm values that point it at this zone."
  value = {
    provider      = { name = "aws" }
    domainFilters = [var.zone_name]
    env           = [{ name = "AWS_DEFAULT_REGION", value = var.context.region }]
    extraArgs     = ["--aws-zone-type=public", "--zone-id-filter=${aws_route53_zone.this.zone_id}"]
  }
}
