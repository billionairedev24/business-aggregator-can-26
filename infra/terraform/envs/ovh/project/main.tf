# OPTIONAL (S-161): orders the Public Cloud project when the owner has none — the default path is a project id given
# as OVH_SERVICE_NAME. Run only by scripts/ovh/project.sh (make ovh-gh-setup with an empty OVH_SERVICE_NAME), which
# first looks for a project with this description through the API and adopts it, so a re-run never orders a second
# one. A project costs nothing by itself; the order is paid with the account's default payment method. The token needs
# the order rights for this (docs/runbooks/ovh.md § Today, step 2 — the variant with an empty project id).

locals {
  loc = jsondecode(file("${path.module}/../../../stacks/ovh/locations.json"))[var.ovh_location]
}

provider "ovh" {
  endpoint = local.loc.api_endpoint
}

data "ovh_me" "account" {}

data "ovh_order_cart" "cart" {
  ovh_subsidiary = data.ovh_me.account.ovh_subsidiary
}

data "ovh_order_cart_product_plan" "project" {
  cart_id        = data.ovh_order_cart.cart.id
  price_capacity = "renew"
  product        = "cloud"
  plan_code      = "project.2018"
}

resource "ovh_cloud_project" "dryrun" {
  ovh_subsidiary = data.ovh_order_cart.cart.ovh_subsidiary
  description    = var.description

  plan {
    duration     = data.ovh_order_cart_product_plan.project.selected_price[0].duration
    plan_code    = data.ovh_order_cart_product_plan.project.plan_code
    pricing_mode = data.ovh_order_cart_product_plan.project.selected_price[0].pricing_mode
  }
}
