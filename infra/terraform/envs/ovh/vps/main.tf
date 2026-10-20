locals {
  # The location (S-160): API endpoint, Object Storage region, VPS datacentre — infra/terraform/stacks/ovh/locations.json.
  loc = jsondecode(file("${path.module}/../../../stacks/ovh/locations.json"))[var.ovh_location]
  # The Network Firewall where the location offers it; elsewhere ufw on the host is the only firewall (bootstrap-k3s.sh).
  edge_firewall = var.edge_firewall && local.loc.edge_firewall
}

provider "ovh" {
  endpoint = local.loc.api_endpoint
}

# ovh-vps — the cheapest dry run (docs/runbooks/ovh.md, DECISIONS 2026-10-19, S-160): ONE OVHcloud VPS running k3s in
# the location's datacentre (ovh_location: Vint Hill by default, Hillsboro or Beauharnois), every data service on it
# (deploy/helm/northline-data), Object Storage in the same location for uploads and Postgres backups.
#
# The VPS itself is ORDERED IN THE CONSOLE, not here. The provider has an ovh_vps resource that orders through the
# order workflow, but it needs the catalogue's plan code of the current VPS range in the datacentre and a default payment
# method, and an apply commits a month's payment — neither could be checked from here, and a typo orders the wrong
# box. Terraform reads the ordered VPS (vps_service_name) for its address and puts the Network Firewall in front of
# it; scripts/ovh/bootstrap-k3s.sh installs k3s over SSH.

module "ovh" {
  source = "../../../stacks/ovh"

  service_name       = var.service_name
  location           = var.ovh_location
  target             = "ovh-vps"
  bucket_name_suffix = var.bucket_name_suffix
  storage_class_name = "local-path" # k3s's local-path provisioner: volumes on the VPS's NVMe disk
  data_services      = var.data_services
}

data "ovh_vps" "box" {
  count        = var.vps_service_name == "" ? 0 : 1
  service_name = var.vps_service_name
}

locals {
  vps_ipv4 = var.vps_service_name == "" ? null : one([for ip in data.ovh_vps.box[0].ips : ip if !strcontains(ip, ":")])

  # OVHcloud Network Firewall rules, evaluated in sequence order (0 first); the last one drops everything else. It
  # filters inbound packets only, so replies to the box's own connections are let in explicitly: TCP established
  # (image pulls, Let's Encrypt, Stripe), DNS and NTP answers.
  firewall_rules = var.vps_service_name == "" || !local.edge_firewall ? {} : merge(
    { "0-tcp-established" = { sequence = 0, protocol = "tcp", tcp_option = "established", source = null, destination_port = null, source_port = null, action = "permit" } },
    { for i, cidr in var.admin_cidrs : "${i + 1}-ssh-${cidr}" => { sequence = i + 1, protocol = "tcp", tcp_option = null, source = cidr, destination_port = 22, source_port = null, action = "permit" } },
    # The pipeline's runner, for one run only (ci_ssh_cidr; .github/workflows/ovh.yml).
    var.ci_ssh_cidr == "" ? {} : { "9-ssh-ci" = { sequence = 9, protocol = "tcp", tcp_option = null, source = var.ci_ssh_cidr, destination_port = 22, source_port = null, action = "permit" } },
    {
      "4-http"       = { sequence = 4, protocol = "tcp", tcp_option = null, source = null, destination_port = 80, source_port = null, action = "permit" }
      "5-https"      = { sequence = 5, protocol = "tcp", tcp_option = null, source = null, destination_port = 443, source_port = null, action = "permit" }
      "6-dns-reply"  = { sequence = 6, protocol = "udp", tcp_option = null, source = null, destination_port = null, source_port = 53, action = "permit" }
      "7-ntp-reply"  = { sequence = 7, protocol = "udp", tcp_option = null, source = null, destination_port = null, source_port = 123, action = "permit" }
      "8-icmp"       = { sequence = 8, protocol = "icmp", tcp_option = null, source = null, destination_port = null, source_port = null, action = "permit" }
      "19-deny-rest" = { sequence = 19, protocol = "ipv4", tcp_option = null, source = null, destination_port = null, source_port = null, action = "deny" }
    },
  )
}

resource "ovh_ip_firewall" "box" {
  count          = length(local.firewall_rules) > 0 ? 1 : 0
  ip             = "${local.vps_ipv4}/32"
  ip_on_firewall = local.vps_ipv4
  enabled        = true

  lifecycle {
    precondition {
      condition     = length(var.admin_cidrs) > 0
      error_message = "edge_firewall without admin_cidrs would shut SSH: set admin_cidrs (your IP, curl -s ifconfig.me) or edge_firewall = false."
    }
  }
}

resource "ovh_ip_firewall_rule" "box" {
  for_each         = local.firewall_rules
  ip               = ovh_ip_firewall.box[0].ip
  ip_on_firewall   = ovh_ip_firewall.box[0].ip_on_firewall
  sequence         = each.value.sequence
  action           = each.value.action
  protocol         = each.value.protocol
  tcp_option       = each.value.tcp_option
  source           = each.value.source
  destination_port = each.value.destination_port
  source_port      = each.value.source_port
}
