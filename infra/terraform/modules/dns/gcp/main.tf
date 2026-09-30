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

# S-17: external-dns (records for the public hosts) and cert-manager (DNS-01 TXT records) administer this zone's
# records; listing zones needs a project-level read role.
resource "google_dns_managed_zone_iam_member" "record_writers" {
  for_each     = var.record_writers
  project      = var.context.project_id
  managed_zone = google_dns_managed_zone.this.name
  role         = "roles/dns.admin"
  member       = each.value
}

resource "google_project_iam_member" "record_writers_list" {
  for_each = var.record_writers
  project  = var.context.project_id
  role     = "roles/dns.reader"
  member   = each.value
}
