# One VPC, three AZs. Public subnets hold only the load balancers and NAT gateways;
# EKS nodes, RDS, MSK and ElastiCache sit in private subnets and reach the internet via NAT.
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 3)
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"

  name = var.name
  cidr = var.vpc_cidr
  azs  = local.azs

  private_subnets  = [for i in range(3) : cidrsubnet(var.vpc_cidr, 4, i)]       # 10.0.0.0/20, 10.0.16.0/20, 10.0.32.0/20
  public_subnets   = [for i in range(3) : cidrsubnet(var.vpc_cidr, 8, 100 + i)] # 10.0.100.0/24 ...
  database_subnets = [for i in range(3) : cidrsubnet(var.vpc_cidr, 8, 200 + i)] # 10.0.200.0/24 ...

  enable_nat_gateway     = true
  single_nat_gateway     = false # one NAT per AZ: an AZ outage does not cut the others off
  one_nat_gateway_per_az = true

  enable_dns_hostnames = true
  enable_dns_support   = true

  # VPC flow logs: a network-layer audit trail (who talked to whom, accepted + rejected) — a baseline
  # control for a payments platform, and the record you need to investigate an incident or spot
  # exfiltration. Without it the network is a black box after the fact. To CloudWatch with a bounded
  # retention; the module owns the log group + IAM role.
  enable_flow_log                                 = true
  create_flow_log_cloudwatch_log_group            = true
  create_flow_log_cloudwatch_iam_role             = true
  flow_log_traffic_type                           = "ALL"
  flow_log_max_aggregation_interval               = 60
  flow_log_cloudwatch_log_group_retention_in_days = 14

  create_database_subnet_group = true

  # the AWS Load Balancer Controller and cluster autoscaler discover subnets by these tags
  public_subnet_tags = {
    "kubernetes.io/role/elb" = "1"
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb"   = "1"
    "karpenter.sh/discovery"            = var.name
    "kubernetes.io/cluster/${var.name}" = "shared"
  }
}
