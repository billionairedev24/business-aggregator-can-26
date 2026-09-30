# Cloud DNS public managed zone with DNSSEC. Delegate it from the parent zone (or the registrar) with name_servers.

resource "google_dns_managed_zone" "this" {
  project       = var.context.project_id
  name          = var.context.name
  dns_name      = "${var.zone_name}."
  description   = "${var.context.name} (Terraform)"
  visibility    = "public"
  force_destroy = var.context.environment == "dev"
  labels        = var.context.tags

  dnssec_config {
    state = "on"
  }
}
