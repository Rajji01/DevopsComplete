variable "region" {
  description = "AWS region (Mumbai by default)"
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "name" {
  description = "Prefix for every resource name"
  type        = string
  default     = "shopflow"
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "kubernetes_version" {
  type    = string
  default = "1.33"
}

variable "node_instance_types" {
  description = "Instance types for the EKS managed node group"
  type        = list(string)
  default     = ["t3.large"]
}

variable "node_group_size" {
  type    = object({ min = number, desired = number, max = number })
  default = { min = 2, desired = 3, max = 6 }
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_multi_az" {
  description = "Standby replica in a second AZ (doubles the cost, removes the single point of failure)"
  type        = bool
  default     = true
}

variable "cache_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "kafka_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "github_repository" {
  description = "owner/repo allowed to assume the CI role via OIDC"
  type        = string
  default     = "Rajji01/DevopsComplete"
}

variable "domain_name" {
  description = "Public hostname for the API (the ACM certificate is issued for it)"
  type        = string
  default     = "shop.example.com"
}
