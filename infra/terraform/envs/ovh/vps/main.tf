locals {
  # The location (S-160): API endpoint, Object Storage region, VPS datacentre — infra/terraform/stacks/ovh/locations.json.
  loc = jsondecode(file("${path.module}/../../../stacks/ovh/locations.json"))[var.ovh_location]
  # The Network Firewall in front of a VPS where the location offers it; elsewhere — and on a Public Cloud instance,
  # where it is unverified — ufw on the host is the only firewall (bootstrap-k3s.sh).
  edge_firewall = var.server == "vps" && var.edge_firewall && local.loc.edge_firewall
}

provider "ovh" {
  endpoint = local.loc.api_endpoint
}

# ovh-vps — the single-server dry run (docs/runbooks/ovh.md, DECISIONS 2026-10-19, S-160, S-161): ONE server running
# k3s in the location (ovh_location: Vint Hill by default, Hillsboro or Beauharnois), every data service on it
# (deploy/helm/northline-data), Object Storage in the same location for uploads and Postgres backups.
#
# The server (S-161, var.server):
#   instance (default) — a Public Cloud instance made HERE: Ubuntu 24.04, hourly billing, the flavor instance_flavor
#                        (b3-32: 8 vCPU / 32 GB), the pipeline's SSH public key registered with it, a public IPv4.
#                        Nothing in the console; terraform destroy deletes it and billing stops.
#   vps                — a VPS ORDERED IN THE CONSOLE (cheaper by the month), read by vps_service_name.
#
# Why a VPS is not ordered here: The provider has an ovh_vps resource that orders through the
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
  count        = local.vps_given ? 1 : 0
  service_name = var.vps_service_name
}

# ---- server = "instance": the Public Cloud instance -----------------------------------------------------------------
data "ovh_cloud_project_flavors" "box" {
  count        = local.instance && var.instance_flavor_id == "" ? 1 : 0
  service_name = var.service_name
  region       = local.loc.region
  name_filter  = var.instance_flavor
}

data "ovh_cloud_project_images" "box" {
  count        = local.instance && var.instance_image_id == "" ? 1 : 0
  service_name = var.service_name
  region       = local.loc.region
  os_type      = "linux"
}

locals {
  instance  = var.server == "instance"
  vps_given = var.server == "vps" && var.vps_service_name != ""
  # By name in the region (the Linux flavor, not its Windows twin), unless an id is given.
  flavor_id = !local.instance ? null : var.instance_flavor_id != "" ? var.instance_flavor_id : try([for f in data.ovh_cloud_project_flavors.box[0].flavors : f.id if f.name == var.instance_flavor && f.os_type == "linux"][0], null)
  image_id  = !local.instance ? null : var.instance_image_id != "" ? var.instance_image_id : try([for i in data.ovh_cloud_project_images.box[0].images : i.id if i.name == var.instance_image][0], null)
}

resource "ovh_cloud_project_instance" "box" {
  count          = local.instance ? 1 : 0
  service_name   = var.service_name
  region         = local.loc.region
  name           = "northline-ovh-vps"
  billing_period = "hourly" # billing stops with terraform destroy

  flavor {
    flavor_id = local.flavor_id
  }
  boot_from {
    image_id = local.image_id
  }
  ssh_key_create {
    name       = "northline-ovh-vps"
    public_key = var.ssh_public_key
  }
  network {
    public = true
  }

  lifecycle {
    # A newer Ubuntu 24.04 build gets a new image id: never rebuild the server (and its data) for that.
    ignore_changes = [boot_from]

    precondition {
      condition     = var.ssh_public_key != ""
      error_message = "server = instance needs ssh_public_key (the pipeline's deploy key: make ovh-gh-setup TARGET=ovh-vps stores it as OVH_SSH_PUBLIC_KEY)."
    }
    precondition {
      condition     = local.flavor_id != null
      error_message = "instance_flavor ${var.instance_flavor} is not offered in ${local.loc.region} (Public Cloud › Instances › Create shows the flavors)."
    }
    precondition {
      condition     = local.image_id != null
      error_message = "no image named ${var.instance_image} in ${local.loc.region}."
    }
  }
}

locals {
  vps_ipv4 = (
    local.instance ? one([for a in ovh_cloud_project_instance.box[0].addresses : a.ip if a.version == 4]) :
    local.vps_given ? one([for ip in data.ovh_vps.box[0].ips : ip if !strcontains(ip, ":")]) : null
  )

  # OVHcloud Network Firewall rules, evaluated in sequence order (0 first); the last one drops everything else. It
  # filters inbound packets only, so replies to the box's own connections are let in explicitly: TCP established
  # (image pulls, Let's Encrypt, Stripe), DNS and NTP answers.
  firewall_rules = !local.vps_given || !local.edge_firewall ? {} : merge(
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
