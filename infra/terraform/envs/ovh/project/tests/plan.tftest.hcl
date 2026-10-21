# Offline with a mocked provider: terraform init -backend=false && terraform test
mock_provider "ovh" {
  mock_data "ovh_me" {
    defaults = { ovh_subsidiary = "US" }
  }
  mock_data "ovh_order_cart" {
    defaults = { id = "cart-1", ovh_subsidiary = "US" }
  }
  mock_data "ovh_order_cart_product_plan" {
    defaults = {
      plan_code      = "project.2018"
      selected_price = [{ capacities = ["renew"], description = "", duration = "P1M", interval = 1, maximum_quantity = 1, maximum_repeat = 1, minimum_quantity = 1, minimum_repeat = 1, price = [], price_in_ucents = 0, pricing_mode = "default", pricing_type = "consumption" }]
    }
  }
  mock_resource "ovh_cloud_project" {
    defaults = { project_id = "0123456789abcdef0123456789abcdef" }
  }
}

run "orders_one_project_on_the_us_account" {
  command = apply

  assert {
    condition = (
      ovh_cloud_project.dryrun.description == "northline-dryrun" && ovh_cloud_project.dryrun.ovh_subsidiary == "US" &&
      ovh_cloud_project.dryrun.plan[0].plan_code == "project.2018" && ovh_cloud_project.dryrun.plan[0].duration == "P1M" &&
      ovh_cloud_project.dryrun.plan[0].pricing_mode == "default" && output.project_id == "0123456789abcdef0123456789abcdef"
    )
    error_message = "One project.2018 order from the account's subsidiary, named northline-dryrun; its id is the output."
  }
}

run "unknown_location_refused" {
  command = plan

  variables {
    ovh_location = "gra"
  }

  expect_failures = [var.ovh_location]
}
