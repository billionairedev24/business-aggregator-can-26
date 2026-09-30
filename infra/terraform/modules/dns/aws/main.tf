# Route 53 public hosted zone. Delegate it from the parent zone (or the registrar) with the name_servers output.

resource "aws_route53_zone" "this" {
  name          = var.zone_name
  comment       = "${var.context.name} (Terraform)"
  force_destroy = var.context.environment == "dev"
  tags          = var.context.tags
}

# S-17: external-dns (records for the public hosts) and cert-manager (DNS-01 TXT records) may change this zone only.
# The principals are IRSA role ARNs; the policy goes on each role.
resource "aws_iam_role_policy" "record_writers" {
  for_each = var.record_writers
  name     = "route53-${replace(var.zone_name, ".", "-")}"
  role     = element(split("/", each.value), length(split("/", each.value)) - 1)
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ChangeThisZone"
        Effect   = "Allow"
        Action   = ["route53:ChangeResourceRecordSets", "route53:ListResourceRecordSets", "route53:ListTagsForResource"]
        Resource = aws_route53_zone.this.arn
      },
      {
        Sid      = "FollowChanges"
        Effect   = "Allow"
        Action   = ["route53:GetChange"]
        Resource = "arn:aws:route53:::change/*"
      },
      {
        # Zone discovery (external-dns filters by --zone-id-filter; cert-manager looks the zone up by name).
        Sid      = "ListZones"
        Effect   = "Allow"
        Action   = ["route53:ListHostedZones", "route53:ListHostedZonesByName", "route53:ListTagsForResources"]
        Resource = "*"
      },
    ]
  })
}
