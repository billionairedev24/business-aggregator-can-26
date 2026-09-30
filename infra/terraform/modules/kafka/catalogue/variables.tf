variable "catalogue_file" {
  description = "Path of the topic catalogue (default: the repository's deploy/kafka/topics.yaml)."
  type        = string
  default     = null
  nullable    = true
}
