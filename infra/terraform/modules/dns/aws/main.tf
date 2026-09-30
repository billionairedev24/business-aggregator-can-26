# Route 53 public hosted zone. Delegate it from the parent zone (or the registrar) with the name_servers output.

resource "aws_route53_zone" "this" {
  name          = var.zone_name
  comment       = "${var.context.name} (Terraform)"
  force_destroy = var.context.environment == "dev"
  tags          = var.context.tags
}
