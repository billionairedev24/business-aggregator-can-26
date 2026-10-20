variable "ovh_location" {
  description = "The location (locations.json): its API endpoint decides the OVHcloud entity (us-east-va / us-west-or: OVHcloud US, ca-bhs: OVHcloud Canada) the project is ordered from."
  type        = string
  default     = "us-east-va"

  validation {
    condition     = contains(["us-east-va", "us-west-or", "ca-bhs"], var.ovh_location)
    error_message = "ovh_location must be us-east-va or us-west-or (OVHcloud US) or ca-bhs (OVHcloud Canada)."
  }
}

variable "description" {
  description = "The project's name in the console; scripts/ovh/project.sh finds an existing project by it."
  type        = string
  default     = "northline-dryrun"
}
